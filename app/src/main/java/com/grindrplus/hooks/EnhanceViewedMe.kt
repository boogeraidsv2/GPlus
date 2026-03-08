package com.grindrplus.hooks

import android.annotation.SuppressLint
import android.view.View
import android.widget.TextView
import com.grindrplus.core.LogSource
import com.grindrplus.core.Logger
import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.UiHelper.Icon
import com.grindrplus.utils.UiHelper.SNACKBAR_LENGTH_LONG
import com.grindrplus.utils.UiHelper.SnackbarType.NEUTRAL
import com.grindrplus.utils.UiHelper.showSnackbar
import com.grindrplus.utils.hook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.findMethodExact
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setBooleanField

class EnhanceViewedMe : Hook(
    "Enhance Viewed me",
    "Unblur profiles, show distances and total view counts"
) {
    private companion object {
        const val PROFILE_ITEM_FQCN = "ag0.m" // Search for "ViewedMeProfileItem"
        const val RECYCLERVIEW_ADAPTER_FQCN = "ag0.e" // Search for "viewedMeTotalTimeString"
        const val RECYCLERVIEW_VH_FQCN = "ag0.n" // Search for "profileLastViewedTextView"
        const val PROFILE_CLICK_OBSERVER_FQCN =
            "gg0.c0" // Search for the Observer passed to the RV Adapter's LiveData.observe in ViewedMeFragment.onViewCreated
        const val INIT_RIGHT_NOW_FAB_CONTAINER_FQCN =
            "q50.l" // Search for "RadarFragment$initRightNowFabContainer$1$1"
        const val BADGE_INFO_FQCN =
            "com.grindrapp.android.viewedme.presentation.model.ViewedMeBadgeInfo"
        const val ADMIRER_BADGE_INFO_FQCN = "$BADGE_INFO_FQCN\$AdmirerBadgeInfo"
    }

    private val admirerBadgeInfoClass by lazy { findClass(ADMIRER_BADGE_INFO_FQCN) }
    private val initRightNowFabContainerClass by lazy { findClass(INIT_RIGHT_NOW_FAB_CONTAINER_FQCN) }
    private val profileClickObserverClass by lazy { findClass(PROFILE_CLICK_OBSERVER_FQCN) }
    private val profileItemClass by lazy { findClass(PROFILE_ITEM_FQCN) }
    private val recyclerViewAdapterClass by lazy { findClass(RECYCLERVIEW_ADAPTER_FQCN) }
    private val recyclerViewVHClass by lazy { findClass(RECYCLERVIEW_VH_FQCN) }
    private var coordinatorLayoutId = 0 // R.id.coordinator_layout

    override fun init() {
        hideBoostButton()
        hookProfileClick()
        hookViewHolder()
        unblurProfiles()
    }

    // TODO: move to DisableBoosting
    // Boost FAB visibility is controlled by MicroSession state in initRightNowFabContainerMethod,
    // not by RadarUiModel, so it must be hidden from the view directly
    private fun hideBoostButton() {
        runCatching {
            val initRightNowFabContainerMethod = findMethodExact(
                initRightNowFabContainerClass,
                "invokeSuspend",
                Any::class.java
            )

            initRightNowFabContainerMethod.hook(HookStage.AFTER) { param ->
                val radarFragment = getObjectField(param.thisObject(), "j")
                val fragmentBinding = callMethod(radarFragment, "J") // FragmentRadarBinding

                val boostButton = getObjectField(fragmentBinding, "b") as View // microsFab
                boostButton.visibility = View.GONE
            }
        }.onFailure { e ->
            Logger.e("Failed to hide Boost button: ${e.stackTraceToString()}", LogSource.MODULE)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun hookProfileClick() {
        runCatching {
            profileClickObserverClass.hook("onChanged", HookStage.BEFORE) { param ->
                // recyclerViewAdapterClass instance
                val adapter = getObjectField(param.thisObject(), "b")

                // List<ViewedMeProfileItem | ViewedMeUpsellDesignHeaderItem>
                val items = getObjectField(adapter, "A") as List<*>

                val clickEvent = param.arg<Any>(0)
                val position = getIntField(clickEvent, "a")

                val item = items.getOrNull(position)

                if (!profileItemClass.isInstance(item)) return@hook

                val profile = getObjectField(item, "b")
                val profileId = callMethod(profile, "getProfileId") as String

                // Preview profiles don't have ID
                if (profileId.toLongOrNull() != null) return@hook

                // Block click on preview profiles
                param.setResult(null)

                val rootView = callMethod(
                    getObjectField(param.thisObject(), "a"), // ViewedMeFragment
                    "getView"
                ) as View

                if (coordinatorLayoutId == 0) {
                    coordinatorLayoutId = getResource("coordinator_layout", "id")
                }

                val coordinatorLayout = rootView.findViewById(coordinatorLayoutId) ?: rootView

                showSnackbar(
                    message = "This profile is a preview and cannot be opened due to server restrictions.",
                    type = NEUTRAL,
                    duration = SNACKBAR_LENGTH_LONG,
                    icon = Icon.ResId(android.R.drawable.ic_dialog_alert),
                    view = coordinatorLayout
                )
            }
        }.onFailure { e ->
            Logger.e("Failed to hook profile click: ${e.stackTraceToString()}", LogSource.MODULE)
        }
    }

    private fun hookViewHolder() {
        runCatching {
            val bindMethod = findMethodExact(
                recyclerViewVHClass,
                "w",
                Int::class.javaPrimitiveType, // position
                Any::class.java, // ViewedMeProfileItem | ViewedMeUpsellDesignHeaderItem
                Boolean::class.javaPrimitiveType // isLastItem
            )

            bindMethod.hook(HookStage.BEFORE) { param ->
                val item = param.arg<Any>(1)
                if (!profileItemClass.isInstance(item)) return@hook

                val profile = getObjectField(item, "b")

                forceDistanceVisibility(profile)
            }

            bindMethod.hook(HookStage.AFTER) { param ->
                val item = param.arg<Any>(1)
                if (!profileItemClass.isInstance(item)) return@hook

                val profile = getObjectField(item, "b")

                // Search for "Intrinsics.checkNotNullParameter(binding, "binding");" in recyclerViewVHClass
                val viewHolderBinding = getObjectField(param.thisObject(), "c")

                showViewedCountTotal(item, profile, viewHolderBinding)
            }
        }.onFailure { e ->
            Logger.e("Failed to hook view holder: ${e.stackTraceToString()}", LogSource.MODULE)
        }
    }

    private fun unblurProfiles() {
        var constructorUnhook: XC_MethodHook.Unhook? = null

        runCatching {
            constructorUnhook = profileItemClass.declaredConstructors.single()
                .hook(HookStage.BEFORE) { param ->
                    // boolean isPreview
                    param.setArg(1, false)
                }

            // Prevent crash after forcing isPreview to always false
            val getItemIdMethod = findMethodExact(
                recyclerViewAdapterClass,
                "getItemId",
                Int::class.javaPrimitiveType
            )

            getItemIdMethod.hook(HookStage.BEFORE) { param ->
                // List<ViewedMeProfileItem | ViewedMeUpsellDesignHeaderItem>
                val items = getObjectField(param.thisObject(), "A") as List<*>
                val item = items.getOrNull(param.arg(0))

                if (!profileItemClass.isInstance(item)) return@hook

                val profile = getObjectField(item, "b")
                val profileId = callMethod(profile, "getProfileId") as String

                // Restore original method behavior
                if (profileId.toLongOrNull() == null) {
                    param.setResult(callMethod(profile, "getCreated"))
                }
            }
        }.onFailure { e ->
            // Unhook constructor if we can't prevent the crash
            constructorUnhook?.unhook()
            Logger.e("Failed to unblur profiles: ${e.stackTraceToString()}", LogSource.MODULE)
        }
    }

    private fun forceDistanceVisibility(profile: Any) {
        runCatching {
            setBooleanField(profile, "showDistance", true)
        }.onFailure { e ->
            Logger.e(
                "Failed to force distance visibility: ${e.stackTraceToString()}",
                LogSource.MODULE
            )
        }
    }

    private fun showViewedCountTotal(item: Any, profile: Any, viewHolderBinding: Any) {
        runCatching {
            val totalCount = getIntField(profile, "viewedCountTotal")
            val badgeInfo = getObjectField(item, "j") ?: return

            if (admirerBadgeInfoClass.isInstance(badgeInfo)) {
                val badgeInfoBinding = getObjectField(viewHolderBinding, "j")

                val badgeTextView = getObjectField(badgeInfoBinding, "c") as TextView
                badgeTextView.text = totalCount.toString()
            }
        }.onFailure {
            Logger.e(
                "Failed to show total view counts: ${it.stackTraceToString()}",
                LogSource.MODULE
            )
        }
    }
}
