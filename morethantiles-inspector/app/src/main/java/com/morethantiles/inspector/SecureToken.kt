package com.morethantiles.inspector

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureToken(private val context:Context) {
    private val name="morethantiles-inspector-gateway-v1"
    private val prefs=context.getSharedPreferences("secure_connection",Context.MODE_PRIVATE)
    private fun secretKey():SecretKey{
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val saved=ks.getKey(name,null) as? SecretKey
        if(saved!=null)return saved
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(name,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun read():String{
        return try{
            val encoded=prefs.getString("token",null)?:return ""
            val bytes=Base64.decode(encoded,Base64.NO_WRAP)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,secretKey(),
                GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
        }catch(_:Exception){""}
    }
    fun write(token:String){
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,secretKey())
        val bytes=cipher.iv+cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("token",Base64.encodeToString(bytes,Base64.NO_WRAP)).apply()
    }
}
