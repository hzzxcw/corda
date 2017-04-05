package net.corda.node.services.config

import com.google.common.net.HostAndPort
import com.typesafe.config.Config
import net.corda.core.div
import net.corda.core.node.NodeVersionInfo
import net.corda.core.node.services.ServiceInfo
import net.corda.node.internal.NetworkMapInfo
import net.corda.node.internal.Node
import net.corda.node.serialization.NodeClock
import net.corda.node.services.messaging.CertificateChainCheckPolicy
import net.corda.node.services.network.NetworkMapService
import net.corda.node.utilities.TestClock
import net.corda.nodeapi.User
import net.corda.nodeapi.config.getOrElse
import net.corda.nodeapi.config.getValue
import java.net.URL
import java.nio.file.Path
import java.util.*

enum class VerifierType {
    InMemory,
    OutOfProcess
}

interface NodeConfiguration : net.corda.nodeapi.config.SSLConfiguration {
    val baseDirectory: Path
    override val certificatesDirectory: Path get() = baseDirectory / "certificates"
    val myLegalName: String
    val networkMapService: NetworkMapInfo?
    val nearestCity: String
    val emailAddress: String
    val exportJMXto: String
    val dataSourceProperties: Properties
    val rpcUsers: List<User>
    val devMode: Boolean
    val certificateSigningService: URL
    val certificateChainCheckPolicies: Map<String, CertificateChainCheckPolicy>
    val verifierType: VerifierType
}

/**
 * [baseDirectory] is not retrieved from the config file but rather from a command line argument.
 */
class FullNodeConfiguration(override val baseDirectory: Path, val config: Config) : NodeConfiguration {
    override val myLegalName: String by config
    override val nearestCity: String by config
    override val emailAddress: String by config
    override val exportJMXto: String get() = "http"
    override val keyStorePassword: String by config
    override val trustStorePassword: String by config
    override val dataSourceProperties: Properties by config
    override val devMode: Boolean by config.orElse { false }
    override val certificateSigningService: URL by config
    override val networkMapService: NetworkMapInfo? by config
    override val rpcUsers: List<User> by config.orElse { emptyList<User>() }
//    override val certificateChainCheckPolicies = config.getOptionalConfig("certificateChainCheckPolicies")?.run {
//        entrySet().associateByTo(HashMap(), { it.key }, { parseCertificateChainCheckPolicy(getConfig(it.key)) })
//    } ?: emptyMap<String, CertificateChainCheckPolicy>()
    override val certificateChainCheckPolicies: Map<String, CertificateChainCheckPolicy> get() = emptyMap()
    override val verifierType: VerifierType by config
    val useHTTPS: Boolean by config
    val p2pAddress: HostAndPort by config
    val rpcAddress: HostAndPort? by config
    val webAddress: HostAndPort by config
    // TODO This field is slightly redundant as p2pAddress is sufficient to hold the address of the node's MQ broker.
    // Instead this should be a Boolean indicating whether that broker is an internal one started by the node or an external one
    val messagingServerAddress: HostAndPort? by config
    val extraAdvertisedServiceIds: List<String> by config
    val useTestClock: Boolean by config.getOrElse { false }
    val notaryNodeId: Int? by config
    val notaryNodeAddress: HostAndPort? by config
    val notaryClusterAddresses: List<HostAndPort> by config

    init {
        // This is a sanity feature do not remove.
        require(!useTestClock || devMode) { "Cannot use test clock outside of dev mode" }
        // TODO Move this to ArtemisMessagingServer
        rpcUsers.forEach {
            require(it.username.matches("\\w+".toRegex())) { "Username ${it.username} contains invalid characters" }
        }
    }

    fun createNode(nodeVersionInfo: NodeVersionInfo): Node {
        val advertisedServices = extraAdvertisedServiceIds
                .filter(String::isNotBlank)
                .map { ServiceInfo.parse(it) }
                .toMutableSet()
        if (networkMapService == null) advertisedServices.add(ServiceInfo(NetworkMapService.type))

        return Node(this, advertisedServices, nodeVersionInfo, if (useTestClock) TestClock() else NodeClock())
    }
}

private fun parseCertificateChainCheckPolicy(config: Config): CertificateChainCheckPolicy {
    val policy = config.getString("policy")
    return when (policy) {
        "Any" -> CertificateChainCheckPolicy.Any
        "RootMustMatch" -> CertificateChainCheckPolicy.RootMustMatch
        "LeafMustMatch" -> CertificateChainCheckPolicy.LeafMustMatch
        "MustContainOneOf" -> CertificateChainCheckPolicy.MustContainOneOf(config.getStringList("trustedAliases").toSet())
        else -> throw IllegalArgumentException("Invalid certificate chain check policy $policy")
    }
}
