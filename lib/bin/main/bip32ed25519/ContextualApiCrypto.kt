/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package bip32ed25519

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.utils.LibraryLoader
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

enum class KeyContext(val value: Int) {
    Address(0),
    Identity(1),
    TESTVECTOR_1(2),
    TESTVECTOR_2(3),
    TESTVECTOR_3(4),
}

@Serializable data class ChannelKeys(val tx: ByteArray, val rx: ByteArray) {}

enum class Encoding {
    CBOR,
    MSGPACK,
    BASE64,
    NONE
}

const val ERROR_TAGS_FOUND = "Error: Algorand-specific tags found"

@Serializable data class SignMetadata(val encoding: Encoding, val schema: Map<String, String>) {}

class ContextualApiCrypto {
    internal val lazySodium: LazySodiumJava
    internal var seed: ByteArray = ByteArray(32)

    init {
        this.lazySodium = LazySodiumJava(SodiumJava(LibraryLoader.Mode.BUNDLED_ONLY))
    }

    public fun setSeed(seed: ByteArray) {
        this.seed = seed
    }

    fun crypto_core_ed25519_scalar_add(a: ByteArray, b: ByteArray): ByteArray {
        return this.lazySodium.cryptoCoreEd25519ScalarAdd(a, b).toByteArray()
    }

    fun crypto_core_ed25519_scalar_mul(a: ByteArray, b: ByteArray): ByteArray {
        return this.lazySodium.cryptoCoreEd25519ScalarMul(a, b).toByteArray()
    }

    fun crypto_core_ed25519_scalar_reduce(a: BigInteger): ByteArray {
        return this.lazySodium.cryptoCoreEd25519ScalarReduce(a).toByteArray()
    }

    fun harden(num: Int): Int = 0x80000000.toInt() + num

    fun getBIP44PathFromContext(context: KeyContext, account: Int, keyIndex: Int): List<Int> {
        return when (context) {
            KeyContext.Address -> listOf(harden(44), harden(283), harden(account), 0, keyIndex)
            KeyContext.Identity -> listOf(harden(44), harden(0), harden(account), 0, keyIndex)
            else -> throw IllegalArgumentException("Invalid context")
        }
    }

    /**
     * Reference of BIP32-Ed25519 Hierarchical Deterministic Keys over a Non-linear Keyspace
     *
     * @see section V. BIP32-Ed25519: Specification;
     *
     * A) Root keys
     *
     * @param seed
     * - 256 bite seed generated from BIP39 Mnemonic
     * @returns
     * - Extended root key (kL, kR, c) where kL is the left 32 bytes of the root key, kR is the
     * right 32 bytes of the root key, and c is the chain code. Total 96 bytes
     */
    fun fromSeed(seed: ByteArray): ByteArray {
        // k = H512(seed)
        var k = MessageDigest.getInstance("SHA-512").digest(seed)
        var kL = k.sliceArray(0 until 32)
        var kR = k.sliceArray(32 until 64)

        // While the third highest bit of the last byte of kL is not zero
        while (kL[31].toInt() and 0b00100000 != 0) {
            val hmac = Mac.getInstance("HmacSHA512")
            hmac.init(SecretKeySpec(kL, "HmacSHA512"))
            k = hmac.doFinal(kR)
            kL = k.sliceArray(0 until 32)
            kR = k.sliceArray(32 until 64)
        }

        // clamp
        // Set the bits in kL as follows:
        // little Endianess
        kL[0] =
                (kL[0].toInt() and 0b11111000)
                        .toByte() // the lowest 3 bits of the first byte of kL are cleared
        kL[31] =
                (kL[31].toInt() and 0b01111111)
                        .toByte() // the highest bit of the last byte is cleared
        kL[31] =
                (kL[31].toInt() or 0b01000000)
                        .toByte() // the second highest bit of the last byte is set

        // chain root code
        // SHA256(0x01||k)
        val c = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(0x01) + seed)
        return kL + kR + c
    }

    /**
     *
     * @see section V. BIP32-Ed25519: Specification
     *
     * @param kl
     * - The scalar
     * @param cc
     * - chain code
     * @param index
     * - non-hardened ( < 2^31 ) index
     * @returns
     * - (z, c) where z is the 64-byte child key and c is the chain code
     */
    fun derivedNonHardened(kl: ByteArray, cc: ByteArray, index: Int): Pair<ByteArray, ByteArray> {
        val data = ByteBuffer.allocate(1 + 32 + 4)
        data.put(1 + 32, index.toByte())

        val pk = this.lazySodium.cryptoScalarMultEd25519BaseNoclamp(kl).toBytes()
        data.position(1)
        data.put(pk)

        data.put(0, 0x02)
        val hmac = Mac.getInstance("HmacSHA512")
        hmac.init(SecretKeySpec(cc, "HmacSHA512"))
        val z = hmac.doFinal(data.array())

        data.put(0, 0x03)
        hmac.init(SecretKeySpec(cc, "HmacSHA512"))
        val childChainCode = hmac.doFinal(data.array())

        return Pair(z, childChainCode)
    }

    /**
     *
     * @see section V. BIP32-Ed25519: Specification
     *
     * @param kl
     * - The scalar (a.k.a private key)
     * @param kr
     * - the right 32 bytes of the root key
     * @param cc
     * - chain code
     * @param index
     * - hardened ( >= 2^31 ) index
     * @returns
     * - (z, c) where z is the 64-byte child key and c is the chain code
     */
    fun deriveHardened(
            kl: ByteArray,
            kr: ByteArray,
            cc: ByteArray,
            index: Int
    ): Pair<ByteArray, ByteArray> {
        val data = ByteBuffer.allocate(1 + 64 + 4)
        data.put(1 + 64, index.toByte())
        data.position(1)
        data.put(kl)
        data.put(kr)

        data.put(0, 0x00)
        val hmac = Mac.getInstance("HmacSHA512")
        hmac.init(SecretKeySpec(cc, "HmacSHA512"))
        val z = hmac.doFinal(data.array())

        data.put(0, 0x01)
        hmac.init(SecretKeySpec(cc, "HmacSHA512"))
        val childChainCode = hmac.doFinal(data.array())

        return Pair(z, childChainCode)
    }

    /**
     * @see section V. BIP32-Ed25519: Specification;
     *
     * subsections:
     *
     * B) Child Keys and C) Private Child Key Derivation
     *
     * @param extendedKey
     * - extended key (kL, kR, c) where kL is the left 32 bytes of the root key the scalar (pvtKey).
     * kR is the right 32 bytes of the root key, and c is the chain code. Total 96 bytes
     * @param index
     * - index of the child key
     * @returns
     * - (kL, kR, c) where kL is the left 32 bytes of the child key (the new scalar), kR is the
     * right 32 bytes of the child key, and c is the chain code. Total 96 bytes
     */
    fun deriveChildNodePrivate(extendedKey: ByteArray, index: Int): ByteArray {
        val kl = extendedKey.sliceArray(0 until 32)
        val kr = extendedKey.sliceArray(32 until 64)
        val cc = extendedKey.sliceArray(64 until 96)

        val (z, childChainCode) =
                if (index < 0x80000000) derivedNonHardened(kl, cc, index)
                else deriveHardened(kl, kr, cc, index)

        val chainCode = childChainCode.sliceArray(32 until 64)
        val zl = z.sliceArray(0 until 32)
        val zr = z.sliceArray(32 until 64)

        // left = kl + 8 * trunc28(zl)
        // right = zr + kr
        val left =
                BigInteger(1, kl) +
                        BigInteger(1, zl.sliceArray(0 until 28)) * BigInteger.valueOf(8L)
        var right = BigInteger(1, kr) + BigInteger(1, zr)

        // just padding
        if (right.bitLength() / 8 < 32) {
            right = right.shiftLeft(8)
        }

        return ByteBuffer.allocate(96)
                .put(left.toByteArray())
                .put(right.toByteArray())
                .put(chainCode)
                .array()
    }

    /**
     * Derives a child key from the root key based on BIP44 path
     *
     * @param rootKey
     * - root key in extended format (kL, kR, c). It should be 96 bytes long
     * @param bip44Path
     * - BIP44 path (m / purpose' / coin_type' / account' / change / address_index). The ' indicates
     * that the value is hardened
     * @param isPrivate
     * - if true, return the private key, otherwise return the public key
     * @returns
     * - The public key of 32 bytes. If isPrivate is true, returns the private key instead.
     */
    fun deriveKey(rootKey: ByteArray, bip44Path: List<Int>, isPrivate: Boolean = true): ByteArray {
        var derived = deriveChildNodePrivate(rootKey, bip44Path[0])
        derived = deriveChildNodePrivate(derived, bip44Path[1])
        derived = deriveChildNodePrivate(derived, bip44Path[2])
        derived = deriveChildNodePrivate(derived, bip44Path[3])

        // Public Key SOFT derivations are possible without using the private key of the parentnode
        // Could be an implementation choice.
        // Example:
        // val nodeScalar: ByteArray = derived.sliceArray(0 until 32)
        // val nodePublic: ByteArray =
        // this.lazySodium.cryptoScalarMultEd25519BaseNoclamp(nodeScalar).toBytes()
        // val nodeCC: ByteArray = derived.sliceArray(64 until 96)

        // // [Public][ChainCode]
        // val extPub: ByteArray = nodePublic + nodeCC
        // val publicKey: ByteArray = deriveChildNodePublic(extPub, bip44Path[4]).sliceArray(0 until
        // 32)

        derived = deriveChildNodePrivate(derived, bip44Path[4])

        val scalar = derived.sliceArray(0 until 32) // scalar == pvtKey
        return if (isPrivate) scalar
        else this.lazySodium.cryptoScalarMultEd25519BaseNoclamp(scalar).toBytes()
    }

    /**
     *
     * @param context
     * - context of the key (i.e Address, Identity)
     * @param account
     * - account number. This value will be hardened as part of BIP44
     * @param keyIndex
     * - key index. This value will be a SOFT derivation as part of BIP44.
     * @returns
     * - public key 32 bytes
     */
    suspend fun keyGen(context: KeyContext, account: Int, keyIndex: Int): ByteArray =
            withContext(Dispatchers.IO) {
                val rootKey: ByteArray = fromSeed(this@ContextualApiCrypto.seed)
                val bip44Path: List<Int> = getBIP44PathFromContext(context, account, keyIndex)

                deriveKey(rootKey, bip44Path, false)
            }

    // /**
    //  * Ref: https://datatracker.ietf.org/doc/html/rfc8032#section-5.1.6
    //  *
    //  * Edwards-Curve Digital Signature Algorithm (EdDSA)
    //  *
    //  * @param context
    //  * - context of the key (i.e Address, Identity)
    //  * @param account
    //  * - account number. This value will be hardened as part of BIP44
    //  * @param keyIndex
    //  * - key index. This value will be a SOFT derivation as part of BIP44.
    //  * @param data
    //  * - data to be signed in raw bytes
    //  * @param metadata
    //  * - metadata object that describes how `data` was encoded and what schema to use to validate
    //  * against
    //  *
    //  * @returns
    //  * - signature holding R and S, totally 64 bytes
    //  */
    // suspend fun signData(
    //         context: KeyContext,
    //         account: Int,
    //         keyIndex: Int,
    //         data: ByteArray,
    //         metadata: SignMetadata
    // ): Any = // TODO: replace Any
    // withContext(Dispatchers.IO) {
    //             // validate data

    //             // TODO: Re add this data validation logic
    //             // val result = validateData(data, metadata)

    //             // if (result is Error) { // decoding errors
    //             //     throw result
    //             // }

    //             // if (!result) { // failed schema validation
    //             //     throw ERROR_BAD_DATA
    //             // }

    //             // Assuming ready is a CompletableFuture that ensures libsodium is ready
    //             // ready.join()

    //             val rootKey: ByteArray = fromSeed(this@ContextualApiCrypto.seed)
    //             val bip44Path: List<Int> = getBIP44PathFromContext(context, account, keyIndex)
    //             val raw: ByteArray = deriveKey(rootKey, bip44Path, true)

    //             val scalar = raw.sliceArray(0 until 32)
    //             val c = raw.sliceArray(32 until 64)

    //             // \(1): pubKey = scalar * G (base point, no clamp)
    //             val publicKey =
    // this@ContextualApiCrypto.lazySodium.cryptoScalarMultEd25519BaseNoclamp(scalar).toBytes()

    //             // \(2): h = hash(c + msg) mod q
    //             val hash = BigInteger(1, MessageDigest.getInstance("SHA-512").digest(c + data))
    //             val q =
    //                     BigInteger(
    //
    // "7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFED",
    //                             16
    //                     )
    //             val rBigInt = hash.mod(q)

    //             // fill 32 bytes of r
    //             // convert to ByteArray
    //             val r = ByteArray(32)
    //             val rBString = rBigInt.toString(16).padStart(64, '0') // convert to hex

    //             for (i in r.indices) {
    //                 r[i] = DatatypeConverter.parseHexBinary(rBString.substring(i * 2, i * 2 +
    // 2))[0]
    //             }

    //             // \(4):  R = r * G (base point, no clamp)
    //             val R = this@ContextualApiCrypto.cryptoScalarMultEd25519BaseNoclamp(r).toBytes()

    //             var h = MessageDigest.getInstance("SHA-512").digest(R + publicKey + data)
    //             h = this@ContextualApiCrypto.crypto_core_ed25519_scalar_reduce(BigInteger(1, h))

    //             // \(5): S = (r + h * k) mod q
    //             val S =
    //                     this@ContextualApiCrypto.crypto_core_ed25519_scalar_add(
    //                             r,
    //                             this@ContextualApiCrypto.crypto_core_ed25519_scalar_mul(h,
    // scalar)
    //                     )

    //             R + S
    //         }

    /**
     * SAMPLE IMPLEMENTATION to show how to validate data with encoding and schema, using base64 as
     * an example
     *
     * @param message
     * @param metadata
     * @returns
     */
    private fun validateData(message: ByteArray, metadata: SignMetadata): Boolean {
        // Check that decoded doesn't include the following prefixes: TX, MX, progData, Program
        // These prefixes are reserved for the protocol

        // if (this.hasAlgorandTags(message)) {
        //     throw IllegalArgumentException(ERROR_TAGS_FOUND)
        // }
        //
        // val decoded: ByteArray
        // when (metadata.encoding) {
        //     Encoding.BASE64 ->
        //             decoded = Base64.getDecoder().decode(message.toString(Charsets.UTF_8))
        //     Encoding.MSGPACK ->
        //             decoded =
        //                     MessagePack.newDefaultUnpacker(message)
        //                             .unpackValue()
        //                             .asBinaryValue()
        //                             .asByteArray()
        //     Encoding.NONE -> decoded = message
        //     else -> throw IllegalArgumentException("Invalid encoding")
        // }

        // // Check after decoding too
        // // Some one might try to encode a regular transaction with the protocol reserved prefixes
        // if (this.hasAlgorandTags(decoded)) {
        //     throw IllegalArgumentException(ERROR_TAGS_FOUND)
        // }

        // // validate with schema
        // val mapper = jacksonObjectMapper()
        // val jsonNode = mapper.readValue(decoded.toString(Charsets.UTF_8))
        // val schemaNode = mapper.convertValue(metadata.schema, JsonNode::class.java)

        // val factory = JsonSchemaFactory.byDefault()
        // val validator: JsonValidator = factory.validator
        // val report: ProcessingReport = validator.validate(schemaNode, jsonNode)

        // if (!report.isSuccess) println(report)

        // return report.isSuccess
        return true
    }

    /**
     * Detect if the message has Algorand protocol specific tags
     *
     * @param message
     * - raw bytes of the message
     * @returns
     * - true if message has Algorand protocol specific tags, false otherwise
     */
    private fun hasAlgorandTags(message: ByteArray): Boolean {
        // Check that decoded doesn't include the following prefixes: TX, MX, progData, Program
        val tx = String(message.sliceArray(0..1), Charsets.US_ASCII)
        val mx = String(message.sliceArray(0..1), Charsets.US_ASCII)
        val progData = String(message.sliceArray(0..7), Charsets.US_ASCII)
        val program = String(message.sliceArray(0..6), Charsets.US_ASCII)

        return tx == "TX" || mx == "MX" || progData == "progData" || program == "Program"
    }

    /**
     * Wrapper around libsodium basic signature verification
     *
     * Any lib or system that can verify EdDSA signatures can be used
     *
     * @param signature
     * - raw 64 bytes signature (R, S)
     * @param message
     * - raw bytes of the message
     * @param publicKey
     * - raw 32 bytes public key (x,y)
     * @returns true if signature is valid, false otherwise
     */
    fun verifyWithPublicKey(
            signature: ByteArray,
            message: ByteArray,
            publicKey: ByteArray
    ): Boolean {
        return this.lazySodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)
    }

    /**
     * Function to perform ECDH against a provided public key
     *
     * ECDH reference link: https://en.wikipedia.org/wiki/Elliptic-curve_Diffie%E2%80%93Hellman
     *
     * It creates a shared secret between two parties. Each party only needs to be aware of the
     * other's public key. This symmetric secret can be used to derive a symmetric key for
     * encryption and decryption. Creating a private channel between the two parties.
     *
     * @param context
     * - context of the key (i.e Address, Identity)
     * @param account
     * - account number. This value will be hardened as part of BIP44
     * @param keyIndex
     * - key index. This value will be a SOFT derivation as part of BIP44.
     * @param otherPartyPub
     * - raw 32 bytes public key of the other party
     * @returns
     * - raw 32 bytes shared secret
     */
    suspend fun ECDH(
            context: KeyContext,
            account: Int,
            keyIndex: Int,
            otherPartyPub: ByteArray
    ): ByteArray {
        // NaCl.sodium() // Initialize sodium

        val rootKey: ByteArray = fromSeed(this.seed)

        val bip44Path: List<Int> = getBIP44PathFromContext(context, account, keyIndex)
        val childKey: ByteArray = this.deriveKey(rootKey, bip44Path, true)

        val scalar: ByteArray = childKey.sliceArray(0 until 32)

        val sharedSecret = ByteArray(32)
        val curve25519Key = ByteArray(32)
        this.lazySodium.convertPublicKeyEd25519ToCurve25519(curve25519Key, otherPartyPub)
        this.lazySodium.cryptoScalarMult(sharedSecret, scalar, curve25519Key)
        return sharedSecret
    }
}