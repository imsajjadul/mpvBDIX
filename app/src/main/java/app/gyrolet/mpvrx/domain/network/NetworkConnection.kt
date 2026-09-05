/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.domain.network

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "network_connections")
@Immutable
data class NetworkConnection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val protocol: NetworkProtocol,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val path: String = "/",
    val isAnonymous: Boolean = false,
    val lastConnected: Long = 0,
    val autoConnect: Boolean = false,
    val useHttps: Boolean = false,
) {
    override fun toString(): String =
        "NetworkConnection(id=$id, name=$name, protocol=$protocol, credentials=<redacted>)"
}

enum class NetworkProtocol(
    val displayName: String,
    val defaultPort: Int,
) {
    SMB("SMB", 445),
    FTP("FTP", 21),
    WEBDAV("WebDAV", 80),
    SFTP("SFTP", 22),

    // Direct HTTP/HTTPS directory index (h5ai/nginx/generic HTML).
    BDIX("BDIX", 80),
}

@Immutable
data class ConnectionStatus(
    val connectionId: Long,
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val error: String? = null,
)
