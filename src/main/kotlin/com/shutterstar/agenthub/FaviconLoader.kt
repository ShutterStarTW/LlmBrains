package com.shutterstar.agenthub

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon

object FaviconLoader {
    private val cache = ConcurrentHashMap<String, Icon>()
    private val notFound = ConcurrentHashMap.newKeySet<String>()

    fun get(agent: CodingAgent): Icon? {
        val domain = agent.faviconKey.ifBlank {
            val host = runCatching { URI(agent.url).host }.getOrNull() ?: return null
            rootDomain(host)
        }
        if (domain in notFound) return null
        return cache.getOrPut(domain) {
            loadResource(domain) ?: run { notFound.add(domain); return null }
        }
    }

    private fun rootDomain(host: String): String {
        val parts = host.split(".")
        if (parts.size <= 2) return host
        if (host.endsWith(".github.io") || host.endsWith(".gitlab.io")) return host
        return parts.takeLast(2).joinToString(".")
    }

    private fun loadResource(domain: String): Icon? = runCatching {
        val stream = FaviconLoader::class.java.getResourceAsStream("/favicons/$domain.png")
            ?: return null
        val img = stream.use { ImageIO.read(it) } ?: return null
        val scaled = img.getScaledInstance(16, 16, java.awt.Image.SCALE_SMOOTH)
        ImageIcon(scaled)
    }.getOrNull()
}