package com.victorious.flash.android.netboot

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

/**
 * Not a unit test: a harness for the QEMU/OVMF netboot check (see .github/workflows/netboot-qemu.yml).
 * Runs only when VFLASH_SERVE_ISO is set; serves that ISO with the app's real route table
 * (EfiBoot.routes + IsoHttpServer) on VFLASH_SERVE_BIND:8080 for VFLASH_SERVE_SECONDS, logging
 * every request to VFLASH_SERVE_LOG.
 */
class NetbootServeHarness {
    @Test fun serve() {
        val iso = System.getenv("VFLASH_SERVE_ISO")
        assumeTrue(iso != null)
        val log = File(System.getenv("VFLASH_SERVE_LOG") ?: "serve.log")
        val (routes, plan) = EfiBoot.routes(File(iso!!), "/ubuntu.iso", null)
        log.writeText("plan missing=${plan.missing} routes=${routes.keys}\n")
        val bind = InetAddress.getByName(System.getenv("VFLASH_SERVE_BIND") ?: "127.0.0.1")
        val srv = IsoHttpServer(routes, listOf(bind), 8080) { line -> synchronized(log) { log.appendText("${System.currentTimeMillis()} $line\n") } }
        srv.start()
        log.appendText("listening ${bind.hostAddress}:${srv.boundPort}\n")
        Thread.sleep((System.getenv("VFLASH_SERVE_SECONDS") ?: "600").toLong() * 1000)
        srv.stop()
    }
}
