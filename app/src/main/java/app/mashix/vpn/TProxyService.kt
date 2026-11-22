package com.v2ray.ang.service

/**
 * JNI-мост к libhev-socks5-tunnel.so (взята из официального релиза v2rayNG).
 * Нативная библиотека компилируется с флагом -DPKGNAME=com/v2ray/ang/service
 * и регистрирует свои методы ровно на этом имени класса, поэтому и пакет,
 * и имя класса менять нельзя.
 */
class TProxyService {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        /** Запускает туннель: читает TUN-пакеты из fd и гонит их в SOCKS5 из конфига. */
        @JvmStatic
        external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        external fun TProxyStopService(): Boolean

        @JvmStatic
        external fun TProxyIsRunning(): Boolean

        @JvmStatic
        external fun TProxyGetStats(): LongArray?
    }
}
