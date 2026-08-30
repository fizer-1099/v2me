package com.example.v2rayconfig.model

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * A single saved server entry (vmess://, vless://, or ss:// link),
 * plus the raw Xray JSON config string it maps to.
 */
data class ServerConfig(
    val id: String,
    val remark: String,
    val protocol: String,      // "vmess", "vless", or "shadowsocks"
    val address: String,
    val port: Int,
    val rawLink: String,       // original share link, kept for re-export/editing
    val xrayConfigJson: String, // full outbound config Xray-core expects
    val useFragment: Boolean = false, // TLS ClientHello fragmentation (helps against DPI)
    val source: String = "manual" // "manual" or "subscription" — lets us refresh subscription entries without losing manual ones
)

object ConfigParser {

    /** Parses a vmess://, vless://, or ss:// share link into a ServerConfig. */
    fun parse(link: String, useFragment: Boolean = false, localPort: Int = 10808): ServerConfig {
        val trimmed = link.trim()
        return when {
            trimmed.startsWith("vmess://") -> parseVmess(trimmed, useFragment, localPort)
            trimmed.startsWith("vless://") -> parseVless(trimmed, useFragment, localPort)
            trimmed.startsWith("ss://") -> parseShadowsocks(trimmed, useFragment, localPort)
            else -> throw IllegalArgumentException("Unsupported link type. Supported: vmess://, vless://, ss://")
        }
    }

    private fun parseVmess(link: String, useFragment: Boolean, localPort: Int = 10808): ServerConfig {
        val b64 = link.removePrefix("vmess://")
        val decoded = String(Base64.decode(b64, Base64.DEFAULT))
        val json = JSONObject(decoded)

        val address = json.optString("add")
        val port = json.optString("port").toIntOrNull() ?: 443
        val remark = json.optString("ps", "$address:$port")
        val uuid = json.optString("id")
        val alterId = json.optString("aid", "0").toIntOrNull() ?: 0
        val network = json.optString("net", "tcp")
        val tls = json.optString("tls", "")
        val path = json.optString("path", "")
        val host = json.optString("host", "")

        val outbound = buildOutboundJson(
            protocol = "vmess",
            address = address, port = port, uuid = uuid, alterId = alterId,
            network = network, security = tls, path = path, host = host,
            useFragment = useFragment, localPort = localPort
        )

        return ServerConfig(
            id = java.util.UUID.randomUUID().toString(), remark = remark,
            protocol = "vmess", address = address, port = port,
            rawLink = link, xrayConfigJson = outbound, useFragment = useFragment
        )
    }

    private fun parseVless(link: String, useFragment: Boolean, localPort: Int = 10808): ServerConfig {
        // vless://uuid@host:port?params#remark
        val uri = URI(link)
        val uuid = uri.userInfo
        val address = uri.host
        val port = uri.port
        val remark = if (uri.fragment != null) URLDecoder.decode(uri.fragment, "UTF-8") else "$address:$port"

        val query = (uri.query ?: "").split("&").filter { it.isNotBlank() }
            .associate {
                val (k, v) = it.split("=", limit = 2).let { p -> p[0] to (p.getOrNull(1) ?: "") }
                k to URLDecoder.decode(v, "UTF-8")
            }

        val network = query["type"] ?: "tcp"
        // "security" can be: "", "tls", or "reality" — REALITY is the option
        // that currently works best against active-probing DPI (e.g. in Iran)
        // because it presents a real, unmodified TLS handshake of a genuine
        // site (the "target"/dest) to any observer or prober.
        val security = query["security"] ?: ""
        val path = query["path"] ?: ""
        val host = query["host"] ?: ""
        val flow = query["flow"] ?: ""          // e.g. "xtls-rprx-vision"
        val sni = query["sni"] ?: host
        val fingerprint = query["fp"] ?: "chrome"
        val publicKey = query["pbk"] ?: ""       // REALITY public key
        val shortId = query["sid"] ?: ""         // REALITY short id
        val spiderX = query["spx"] ?: ""         // REALITY spiderX
        val serviceName = query["serviceName"] ?: "" // for grpc

        val outbound = buildOutboundJson(
            protocol = "vless",
            address = address, port = port, uuid = uuid, alterId = 0,
            network = network, security = security, path = path, host = host,
            flow = flow, sni = sni, fingerprint = fingerprint,
            publicKey = publicKey, shortId = shortId, spiderX = spiderX,
            serviceName = serviceName, useFragment = useFragment, localPort = localPort
        )

        return ServerConfig(
            id = java.util.UUID.randomUUID().toString(), remark = remark,
            protocol = "vless", address = address, port = port,
            rawLink = link, xrayConfigJson = outbound, useFragment = useFragment
        )
    }

    private fun parseShadowsocks(link: String, useFragment: Boolean, localPort: Int = 10808): ServerConfig {
        // ss://base64(method:password)@host:port#remark  OR fully base64'd after ss://
        var body = link.removePrefix("ss://")
        val hashIdx = body.indexOf('#')
        val remark = if (hashIdx >= 0) URLDecoder.decode(body.substring(hashIdx + 1), "UTF-8") else ""
        if (hashIdx >= 0) body = body.substring(0, hashIdx)

        val atIdx = body.lastIndexOf('@')
        val methodPassRaw: String
        val hostPort: String
        if (atIdx >= 0) {
            methodPassRaw = body.substring(0, atIdx)
            hostPort = body.substring(atIdx + 1)
        } else {
            // whole thing is base64: method:password@host:port
            val decodedAll = String(Base64.decode(body, Base64.URL_SAFE.or(Base64.NO_PADDING)))
            val at2 = decodedAll.lastIndexOf('@')
            methodPassRaw = decodedAll.substring(0, at2)
            hostPort = decodedAll.substring(at2 + 1)
            return buildShadowsocksConfig(methodPassRaw, hostPort, remark, link, useFragment, alreadyDecoded = true, localPort = localPort)
        }
        return buildShadowsocksConfig(methodPassRaw, hostPort, remark, link, useFragment, alreadyDecoded = false, localPort = localPort)
    }

    private fun buildShadowsocksConfig(
        methodPassRaw: String, hostPort: String, remarkIn: String,
        link: String, useFragment: Boolean, alreadyDecoded: Boolean, localPort: Int = 10808
    ): ServerConfig {
        val methodPass = if (alreadyDecoded) methodPassRaw
        else String(Base64.decode(methodPassRaw, Base64.URL_SAFE.or(Base64.NO_PADDING).or(Base64.NO_WRAP)))
        val (method, password) = methodPass.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val (address, portStr) = hostPort.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "443" } }
        val port = portStr.toIntOrNull() ?: 443
        val remark = remarkIn.ifBlank { "$address:$port" }

        val outboundSettings = JSONObject().apply {
            put("servers", org.json.JSONArray().put(JSONObject().apply {
                put("address", address)
                put("port", port)
                put("method", method)
                put("password", password)
            }))
        }
        val outbound = JSONObject().apply {
            put("protocol", "shadowsocks")
            put("settings", outboundSettings)
            put("streamSettings", JSONObject().apply {
                put("network", "tcp")
                put("sockopt", JSONObject().apply { put("tcpFastOpen", true) })
            })
            put("tag", "proxy")
            put("mux", JSONObject().apply {
                put("enabled", true)
                put("concurrency", 8)
            })
        }
        val root = buildRootConfig(outbound, useFragment, address, port, localPort)

        return ServerConfig(
            id = java.util.UUID.randomUUID().toString(), remark = remark,
            protocol = "shadowsocks", address = address, port = port,
            rawLink = link, xrayConfigJson = root, useFragment = useFragment
        )
    }

    /**
     * Builds a minimal but complete Xray-core JSON config (inbound socks/http
     * proxy on localhost + the outbound proxy) that Libv2ray can start directly.
     *
     * Anti-censorship notes:
     * - security="reality" avoids a real TLS cert entirely; the server borrows
     *   the TLS identity of a real site, so DPI sees a normal handshake.
     * - flow="xtls-rprx-vision" (REALITY + vless) resists traffic-shape analysis.
     * - useFragment=true splits the outgoing TLS ClientHello into small
     *   fragments with delays, which helps against DPI that keys off the
     *   size/shape of the first TLS packet — a technique widely used to get
     *   through Iranian ISPs' filtering.
     */
    private fun buildOutboundJson(
        protocol: String,
        address: String,
        port: Int,
        uuid: String,
        alterId: Int,
        network: String,
        security: String,
        path: String,
        host: String,
        flow: String = "",
        sni: String = "",
        fingerprint: String = "chrome",
        publicKey: String = "",
        shortId: String = "",
        spiderX: String = "",
        serviceName: String = "",
        useFragment: Boolean = false,
        localPort: Int = 10808
    ): String {
        val streamSettings = JSONObject().apply {
            put("network", network)
            when (security) {
                "tls" -> {
                    put("security", "tls")
                    put("tlsSettings", JSONObject().apply {
                        if (sni.isNotBlank()) put("serverName", sni)
                        put("fingerprint", fingerprint)
                    })
                }
                "reality" -> {
                    put("security", "reality")
                    put("realitySettings", JSONObject().apply {
                        if (sni.isNotBlank()) put("serverName", sni)
                        put("fingerprint", fingerprint)
                        put("publicKey", publicKey)
                        if (shortId.isNotBlank()) put("shortId", shortId)
                        if (spiderX.isNotBlank()) put("spiderX", spiderX)
                    })
                }
            }
            if (network == "ws") {
                put("wsSettings", JSONObject().apply {
                    put("path", path.ifBlank { "/" })
                    if (host.isNotBlank()) {
                        put("headers", JSONObject().apply { put("Host", host) })
                    }
                })
            }
            if (network == "grpc") {
                put("grpcSettings", JSONObject().apply {
                    put("serviceName", serviceName)
                })
            }
            // TCP Fast Open shaves a round-trip off every new connection to
            // the proxy server — helps overall responsiveness/stability.
            put("sockopt", JSONObject().apply {
                put("tcpFastOpen", true)
            })
        }

        val user = JSONObject().apply {
            put("id", uuid)
            if (protocol == "vmess") put("alterId", alterId)
            if (protocol == "vless") {
                put("encryption", "none")
                if (flow.isNotBlank()) put("flow", flow)
            }
        }

        val vnext = JSONObject().apply {
            put("address", address)
            put("port", port)
            put("users", org.json.JSONArray().put(user))
        }

        val outboundSettings = JSONObject().apply {
            put("vnext", org.json.JSONArray().put(vnext))
        }

        val outbound = JSONObject().apply {
            put("protocol", protocol)
            put("settings", outboundSettings)
            put("streamSettings", streamSettings)
            put("tag", "proxy")
            // Multiplexing reuses one proxy connection for many app requests,
            // which noticeably improves stability/speed when lots of small
            // connections open at once (e.g. loading a page with many assets).
            // NOT compatible with XTLS flow control ("vision"), which needs
            // to see each TCP connection's real framing — so skip mux there.
            if (flow != "xtls-rprx-vision") {
                put("mux", JSONObject().apply {
                    put("enabled", true)
                    put("concurrency", 8)
                })
            }
        }

        return buildRootConfig(outbound, useFragment, address, port, localPort)
    }

    /** Assembles inbounds + the given proxy outbound (+ optional fragment chain) into a full config. */
    private fun buildRootConfig(proxyOutbound: JSONObject, useFragment: Boolean, address: String, port: Int, localPort: Int = 10808): String {
        val outbounds = org.json.JSONArray()

        if (useFragment) {
            // Chain: proxy outbound dials out through a "fragment" freedom
            // outbound, which splits the TLS ClientHello into small pieces
            // with randomized timing. This targets DPI that fingerprints
            // the first TLS packet shape rather than decrypting content.
            val streamSettings = proxyOutbound.getJSONObject("streamSettings")
            val sockopt = if (streamSettings.has("sockopt")) streamSettings.getJSONObject("sockopt") else JSONObject()
            sockopt.put("dialerProxy", "fragment-out")
            streamSettings.put("sockopt", sockopt)
            outbounds.put(proxyOutbound)
            outbounds.put(JSONObject().apply {
                put("protocol", "freedom")
                put("tag", "fragment-out")
                put("settings", JSONObject().apply {
                    put("fragment", JSONObject().apply {
                        put("packets", "tlshello")
                        put("length", "10-20")
                        put("interval", "10-20")
                    })
                })
            })
        } else {
            outbounds.put(proxyOutbound)
        }

        outbounds.put(JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        })
        outbounds.put(JSONObject().apply {
            put("protocol", "blackhole")
            put("tag", "block")
        })

        val inbounds = org.json.JSONArray()

        // TUN inbound: on Android, VpnService hands us a raw TUN file
        // descriptor. AndroidLibXrayLite's CoreController.startLoop(config,
        // tunFd) sets the xray.tun.fd env var for us; this inbound entry is
        // what tells Xray-core to actually attach to that fd and read/write
        // raw IP packets. (Verified against the current Xray-core
        // proxy/tun README — "port"/"listen" are ignored for this inbound.)
        inbounds.put(JSONObject().apply {
            put("port", 0)
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xraytun0")
                put("mtu", 1500)
            })
            put("tag", "tun-in")
        })

        // Secondary local SOCKS inbound — not used for system-wide routing
        // (the tun inbound above handles that), but kept as a convenient
        // local endpoint for this app's own diagnostics: the exit-country
        // check and the periodic health-check both dial through it.
        inbounds.put(JSONObject().apply {
            put("port", localPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply { put("udp", true) })
            put("tag", "socks-in")
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", org.json.JSONArray().put("http").put("tls"))
            })
        })

        // DNS over HTTPS to a resolver less likely to be poisoned/blocked,
        // instead of relying on possibly-tampered local DNS.
        val dns = JSONObject().apply {
            put("servers", org.json.JSONArray().put("https://1.1.1.1/dns-query").put("8.8.8.8"))
        }

        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("dns", dns)
            put("inbounds", inbounds)
            put("outbounds", outbounds)
        }.toString()
    }
}
