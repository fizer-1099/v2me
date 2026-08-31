package com.example.v2rayconfig.model

object SubscriptionPresets {
    data class Preset(val name: String, val url: String)

    val presets = listOf(
        Preset(
            "barry-far/V2ray-Config",
            "https://raw.githubusercontent.com/barry-far/V2ray-config/main/All_Config_base64_Sub.txt"
        ),
        Preset(
            "Epodonios/v2ray-configs",
            "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt"
        ),
        Preset(
            "mahdibland/V2RayAggregator",
            "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt"
        ),
        Preset(
            "ShadowException/VPN",
            "https://raw.githubusercontent.com/ShadowException/VPN/main/configs.txt"
        )
    )
}
