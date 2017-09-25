package net.corda.nodeapi.internal.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.ClosureSerializer
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer
import com.esotericsoftware.kryo.serializers.FieldSerializer
import de.javakaffee.kryoserializers.ArraysAsListSerializer
import de.javakaffee.kryoserializers.BitSetSerializer
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer
import de.javakaffee.kryoserializers.guava.*
import net.corda.core.contracts.PrivacySalt
import net.corda.core.crypto.CompositeKey
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.serialization.SerializedBytes
import net.corda.core.transactions.NotaryChangeWireTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.NonEmptySet
import net.corda.core.utilities.toNonEmptySet
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jcajce.provider.asymmetric.rsa.BCRSAPrivateCrtKey
import org.bouncycastle.jcajce.provider.asymmetric.rsa.BCRSAPublicKey
import org.bouncycastle.pqc.jcajce.provider.sphincs.BCSphincs256PrivateKey
import org.bouncycastle.pqc.jcajce.provider.sphincs.BCSphincs256PublicKey
import org.objenesis.instantiator.ObjectInstantiator
import org.objenesis.strategy.InstantiatorStrategy
import org.objenesis.strategy.StdInstantiatorStrategy
import org.slf4j.Logger
import sun.security.ec.ECPublicKeyImpl
import sun.security.provider.certpath.X509CertPath
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.Serializable
import java.lang.reflect.Modifier.isPublic
import java.security.cert.CertPath
import java.util.*
import kotlin.collections.ArrayList

object DefaultKryoCustomizer {
    private val pluginRegistries: List<CordaPluginRegistry> by lazy {
        ServiceLoader.load(CordaPluginRegistry::class.java, this.javaClass.classLoader).toList()
    }

    fun customize(kryo: Kryo): Kryo {
        return kryo.apply {
            // Store a little schema of field names in the stream the first time a class is used which increases tolerance
            // for change to a class.
            setDefaultSerializer(CompatibleFieldSerializer::class.java)
            // Take the safest route here and allow subclasses to have fields named the same as super classes.
            fieldSerializerConfig.cachedFieldNameStrategy = FieldSerializer.CachedFieldNameStrategy.EXTENDED

            instantiatorStrategy = CustomInstantiatorStrategy()

            // Required for HashCheckingStream (de)serialization.
            // Note that return type should be specifically set to InputStream, otherwise it may not work, i.e. val aStream : InputStream = HashCheckingStream(...).
            addDefaultSerializer(InputStream::class.java, InputStreamSerializer)
            addDefaultSerializer(SerializeAsToken::class.java, SerializeAsTokenSerializer<SerializeAsToken>())
            addDefaultSerializer(Logger::class.java, LoggerSerializer)

            // WARNING: reordering the registrations here will cause a change in the serialized form, since classes
            // with custom serializers get written as registration ids. This will break backwards-compatibility.
            // Please add any new registrations to the end.
            // TODO: re-organise registrations into logical groups before v1.0

            register(Arrays.asList("").javaClass, ArraysAsListSerializer())
            register(SignedTransaction::class.java, SignedTransactionSerializer)
            register(WireTransaction::class.java, WireTransactionSerializer)
            register(SerializedBytes::class.java, SerializedBytesSerializer)
            UnmodifiableCollectionsSerializer.registerSerializers(this)
            ImmutableListSerializer.registerSerializers(this)
            ImmutableSetSerializer.registerSerializers(this)
            ImmutableSortedSetSerializer.registerSerializers(this)
            ImmutableMapSerializer.registerSerializers(this)
            ImmutableMultimapSerializer.registerSerializers(this)
            // InputStream subclasses whitelisting, required for attachments.
            register(BufferedInputStream::class.java, InputStreamSerializer)
            register(Class.forName("sun.net.www.protocol.jar.JarURLConnection\$JarURLInputStream"), InputStreamSerializer)
            noReferencesWithin<WireTransaction>()
            register(ECPublicKeyImpl::class.java, ECPublicKeyImplSerializer)
            register(EdDSAPublicKey::class.java, Ed25519PublicKeySerializer)
            register(EdDSAPrivateKey::class.java, Ed25519PrivateKeySerializer)
            register(CompositeKey::class.java, CompositeKeySerializer)  // Using a custom serializer for compactness
            // Exceptions. We don't bother sending the stack traces as the client will fill in its own anyway.
            register(Array<StackTraceElement>::class, read = { _, _ -> emptyArray() }, write = { _, _, _ -> })
            // This ensures a NonEmptySetSerializer is constructed with an initial value.
            register(NonEmptySet::class.java, NonEmptySetSerializer)
            register(BitSet::class.java, BitSetSerializer())
            register(Class::class.java, ClassSerializer)
            register(FileInputStream::class.java, InputStreamSerializer)
            register(CertPath::class.java, CertPathSerializer)
            register(X509CertPath::class.java, CertPathSerializer)
            register(X500Name::class.java, X500NameSerializer)
            register(X509CertificateHolder::class.java, X509CertificateSerializer)
            register(BCECPrivateKey::class.java, PrivateKeySerializer)
            register(BCECPublicKey::class.java, PublicKeySerializer)
            register(BCRSAPrivateCrtKey::class.java, PrivateKeySerializer)
            register(BCRSAPublicKey::class.java, PublicKeySerializer)
            register(BCSphincs256PrivateKey::class.java, PrivateKeySerializer)
            register(BCSphincs256PublicKey::class.java, PublicKeySerializer)
            register(sun.security.ec.ECPublicKeyImpl::class.java, PublicKeySerializer)
            register(NotaryChangeWireTransaction::class.java, NotaryChangeWireTransactionSerializer)
            register(PartyAndCertificate::class.java, PartyAndCertificateSerializer)

            // Don't deserialize PrivacySalt via its default constructor.
            register(PrivacySalt::class.java, PrivacySaltSerializer)

            kryo.register(java.lang.invoke.SerializedLambda::class.java)
            register(ClosureSerializer.Closure::class.java, CordaClosureSerializer)

            val customization = KryoSerializationCustomization(this)
            pluginRegistries.forEach { it.customizeSerialization(customization) }
        }
    }

    private class CustomInstantiatorStrategy : InstantiatorStrategy {
        private val fallbackStrategy = StdInstantiatorStrategy()
        // Use this to allow construction of objects using a JVM backdoor that skips invoking the constructors, if there
        // is no no-arg constructor available.
        private val defaultStrategy = Kryo.DefaultInstantiatorStrategy(fallbackStrategy)
        override fun <T> newInstantiatorOf(type: Class<T>): ObjectInstantiator<T> {
            // However this doesn't work for non-public classes in the java. namespace
            val strat = if (type.name.startsWith("java.") && !isPublic(type.modifiers)) fallbackStrategy else defaultStrategy
            return strat.newInstantiatorOf(type)
        }
    }

    private object PartyAndCertificateSerializer : Serializer<PartyAndCertificate>() {
        override fun write(kryo: Kryo, output: Output, obj: PartyAndCertificate) {
            kryo.writeClassAndObject(output, obj.certPath)
        }
        override fun read(kryo: Kryo, input: Input, type: Class<PartyAndCertificate>): PartyAndCertificate {
            return PartyAndCertificate(kryo.readClassAndObject(input) as CertPath)
        }
    }

    private object NonEmptySetSerializer : Serializer<NonEmptySet<Any>>() {
        override fun write(kryo: Kryo, output: Output, obj: NonEmptySet<Any>) {
            // Write out the contents as normal
            output.writeInt(obj.size, true)
            obj.forEach { kryo.writeClassAndObject(output, it) }
        }

        override fun read(kryo: Kryo, input: Input, type: Class<NonEmptySet<Any>>): NonEmptySet<Any> {
            val size = input.readInt(true)
            require(size >= 1) { "Invalid size read off the wire: $size" }
            val list = ArrayList<Any>(size)
            repeat(size) {
                list += kryo.readClassAndObject(input)
            }
            return list.toNonEmptySet()
        }
    }

    /*
     * Avoid deserialising PrivacySalt via its default constructor
     * because the random number generator may not be available.
     */
    private object PrivacySaltSerializer : Serializer<PrivacySalt>() {
        override fun write(kryo: Kryo, output: Output, obj: PrivacySalt) {
            output.writeBytesWithLength(obj.bytes)
        }

        override fun read(kryo: Kryo, input: Input, type: Class<PrivacySalt>): PrivacySalt {
            return PrivacySalt(input.readBytesWithLength())
        }
    }

    object CordaClosureSerializer : ClosureSerializer() {

        val ERROR_MESSAGE = "Unable to serialize Java Lambda expression, unless explicitly declared e.g., Runnable r = (Runnable & Serializable) () -> System.out.println(\"Hello world!\");"

        override fun write(kryo: Kryo, output: Output, target: Any) {

            if (!isSerializable(target)) {
                throw IllegalArgumentException(ERROR_MESSAGE)
            }
            super.write(kryo, output, target)
        }

        private fun isSerializable(target: Any): Boolean {

            return target is Serializable
        }
    }
}
