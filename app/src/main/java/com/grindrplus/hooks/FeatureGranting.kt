package com.grindrplus.hooks

import com.grindrplus.GrindrPlus
import com.grindrplus.core.Config
import com.grindrplus.core.Logger
import com.grindrplus.core.logd
import com.grindrplus.core.logi
import com.grindrplus.core.loge
import com.grindrplus.ui.Utils
import com.grindrplus.utils.Feature
import com.grindrplus.utils.FeatureManager
import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
import com.grindrplus.utils.hookConstructor
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField

class FeatureGranting : Hook(
    "Feature granting",
    "Grant all Grindr features"
) {
    private val isFeatureFlagEnabled = "ih.e" // search for 'implements IsFeatureFlagEnabled {'
    private val upsellsV8Model = "com.grindrapp.android.model.UpsellsV8"
    private val insertsModel = "com.grindrapp.android.model.Inserts"
    private val settingDistanceVisibilityViewModel =
        "com.grindrapp.android.ui.settings.distance.a\$e" // search for 'UiState(distanceVisibility='
    private val featureModel = "com.grindrapp.android.usersession.model.Feature"
    private val tapModel = "com.grindrapp.android.taps.model.Tap"
    private val tapInboxModel = "com.grindrapp.android.taps.data.model.TapsInboxEntity"
    private val alertParams = "P" // search for 'AlertController.AlertParams' in androidx.appcompat.app.AlertDialog
    private val featureManager = FeatureManager()

    override fun init() {
        try {
            initFeatures()
        } catch (t: Throwable) {
            loge("EnableUltimated: initFeatures failed: ${t.javaClass.simpleName}: ${t.message}")
            Logger.writeRaw(t.stackTraceToString())
            return
        }
        logi("EnableUltimated: Feature map initialized")

		// search for 'Assignment.Flag'
        try {
            findClass(isFeatureFlagEnabled).hook("a", HookStage.BEFORE) { param ->
                try {
                    val flagKey = callMethod(param.args()[0], "toString") as String
                    if (featureManager.isManaged(flagKey)) {
                        val enabled = featureManager.isEnabled(flagKey)
                        logd("EnableUltimated: Overriding feature flag '$flagKey' -> $enabled")
                        param.setResult(enabled)
                    }
                } catch (t: Throwable) {
                    loge("EnableUltimated: Feature flag interception failed: ${t.javaClass.simpleName}: ${t.message}")
                    Logger.writeRaw(t.stackTraceToString())
                }
            }
        } catch (t: Throwable) {
            loge("EnableUltimated: Failed to hook IsFeatureFlagEnabled: ${t.javaClass.simpleName}: ${t.message}")
            Logger.writeRaw(t.stackTraceToString())
        }

        try {
            findClass(featureModel).hook("isGranted", HookStage.BEFORE) { param ->
                try {
                    val disallowedFeatures = setOf("DisableScreenshot")
                    val feature = callMethod(param.thisObject(), "toString") as String
                    param.setResult(feature !in disallowedFeatures)
                } catch (t: Throwable) {
                    loge("EnableUltimated: isGranted override failed: ${t.javaClass.simpleName}: ${t.message}")
                    Logger.writeRaw(t.stackTraceToString())
                }
            }
        } catch (t: Throwable) {
            loge("EnableUltimated: Failed to hook feature model: ${t.javaClass.simpleName}: ${t.message}")
            Logger.writeRaw(t.stackTraceToString())
        }

        try {
            findClass(settingDistanceVisibilityViewModel)
                .hookConstructor(HookStage.BEFORE) { param ->
                    try {
                        param.setArg(4, false) // hidePreciseDistance
                    } catch (t: Throwable) {
                        loge("EnableUltimated: Distance visibility override failed: ${t.javaClass.simpleName}: ${t.message}")
                        Logger.writeRaw(t.stackTraceToString())
                    }
                }
        } catch (t: Throwable) {
            loge("EnableUltimated: Failed to hook distance visibility model: ${t.javaClass.simpleName}: ${t.message}")
            Logger.writeRaw(t.stackTraceToString())
        }

        listOf(upsellsV8Model, insertsModel).forEach { model ->
            findClass(model)
                .hook("getMpuFree", HookStage.BEFORE) { param ->
                    param.setResult(0)
                }

            findClass(model)
                .hook("getMpuXtra", HookStage.BEFORE) { param ->
                    param.setResult(0)
                }
        }

        listOf(tapModel, tapInboxModel).forEach { model ->
            findClass(model).hook("isViewable", HookStage.BEFORE) { param ->
                param.setResult(true)
            }
        }

        val boostAlertStringId = Utils.getId(
            "incognito_while_boosting_confilct_warning_message",
            "string",
            GrindrPlus.context
        )

        val boostAlertString = GrindrPlus.context.resources.getString(boostAlertStringId)

        try {
            findClass("androidx.appcompat.app.AlertDialog\$Builder")
                .hook("show", HookStage.BEFORE) { param ->
                    try {
                        val builder = param.thisObject()
                        val alertParams = getObjectField(builder, alertParams)
                        val messageString = getObjectField(alertParams, "mMessage")

                        if (messageString.equals(boostAlertString)) {
                            val dialog = callMethod(builder, "create")
                            val positiveButtonListener = getObjectField(alertParams, "mPositiveButtonListener")

                            val positiveButtonId = XposedHelpers.getStaticIntField(
                                findClass("android.content.DialogInterface"),
                                "BUTTON_POSITIVE"
                            )

                            callMethod(positiveButtonListener, "onClick", dialog, positiveButtonId)

                            param.setResult(dialog)
                        }
                    } catch (t: Throwable) {
                        loge("EnableUltimated: AlertDialog boost bypass failed: ${t.javaClass.simpleName}: ${t.message}")
                        Logger.writeRaw(t.stackTraceToString())
                    }
                }
        } catch (t: Throwable) {
            loge("EnableUltimated: Failed to hook AlertDialog.Builder.show: ${t.javaClass.simpleName}: ${t.message}")
            Logger.writeRaw(t.stackTraceToString())
        }
    }

    private fun initFeatures() {
        featureManager.add(Feature("PasswordComplexity", false))
        featureManager.add(Feature("TimedBans", false))
        featureManager.add(Feature("GenderFlag", true))
        featureManager.add(Feature("ForceApplovinOptOut", true))
        featureManager.add(Feature("RewardedAdViewedMeFeatureFlag", false))
        featureManager.add(Feature("ChatInterstitialFeatureFlag", false))
        featureManager.add(Feature("SideDrawerDeeplinkKillSwitch", true))
        featureManager.add(Feature("SponsoredRoamKillSwitch", true))
        featureManager.add(Feature("UnifiedProfileAvatarFeatureFlag", true))
        featureManager.add(Feature("ApproximateDistanceFeatureFlag", false))
        featureManager.add(Feature("DoxyPEP", true))
        featureManager.add(Feature("CascadeRewriteFeatureFlag", false))
        featureManager.add(Feature("AdsLogs", false))
        featureManager.add(Feature("NonChatEnvironmentAdBannerFeatureFlag", false))
        featureManager.add(Feature("PersistentAdBannerFeatureFlag", false))
        featureManager.add(Feature("ClientTelemetryTracking", false))
        featureManager.add(Feature("LTOAds", false))
        featureManager.add(Feature("SponsorProfileAds", false))
        featureManager.add(Feature("ConversationAds", false))
        featureManager.add(Feature("InboxNativeAds", false))
        featureManager.add(Feature("ReportingLagTime", false))
        featureManager.add(Feature("MrecNewFlow", false))
        featureManager.add(Feature("RunningOnEmulatorFeatureFlag", false))
        featureManager.add(Feature("BannerNewFlow", false))
        featureManager.add(Feature("CalendarUi", true))
        featureManager.add(Feature("CookieTap", getBooleanSetting("enable_cookie_tap", false)))
        featureManager.add(Feature("VipFlag", getBooleanSetting("enable_vip_flag", false)))
        featureManager.add(Feature("PositionFilter", true))
        featureManager.add(Feature("AgeFilter", true))
        featureManager.add(Feature("BanterFeatureGate", false))
        featureManager.add(Feature("TakenOnGrindrWatermarkFlag", false))
        featureManager.add(Feature("gender-filter", true))
        featureManager.add(Feature("enable-chat-summaries", true))
        featureManager.add(Feature("enable-mutual-taps-no-paywall", !getBooleanSetting("enable_interest_section", true)))
        logi("EnableUltimated: Core feature grants loaded")
    }

    private fun getBooleanSetting(key: String, default: Boolean): Boolean {
        val value = Config.get(key, default, true)
        val parsed = when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> default
        }
        logi("EnableUltimated: Setting '$key' raw='$value' parsed=$parsed default=$default")
        return parsed
    }
}