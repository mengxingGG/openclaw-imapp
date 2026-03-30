package ai.openclaw.imapp.data.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "imapp_config")

@Singleton
class ServerConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val SERVER_URL = stringPreferencesKey("server_url")
    private val SESSION_TOKEN = stringPreferencesKey("session_token")
    private val USER_ID = stringPreferencesKey("user_id")
    private val USER_NAME = stringPreferencesKey("user_name")
    private val DEVICE_ID = stringPreferencesKey("device_id")

    val serverUrl: Flow<String?> = context.dataStore.data.map { it[SERVER_URL] }
    val sessionToken: Flow<String?> = context.dataStore.data.map { it[SESSION_TOKEN] }
    val userId: Flow<String?> = context.dataStore.data.map { it[USER_ID] }
    val userName: Flow<String?> = context.dataStore.data.map { it[USER_NAME] }

    private var cachedDeviceId: String? = null

    val deviceId: Flow<String> = flow {
        val id = cachedDeviceId ?: context.dataStore.data.first()[DEVICE_ID]
        if (id != null) {
            cachedDeviceId = id
            emit(id)
        } else {
            val newId = UUID.randomUUID().toString()
            saveDeviceId(newId)
            cachedDeviceId = newId
            emit(newId)
        }
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { it[SERVER_URL] = url.trimEnd('/') }
    }

    suspend fun saveSession(token: String, userId: String, userName: String) {
        context.dataStore.edit {
            it[SESSION_TOKEN] = token
            it[USER_ID] = userId
            it[USER_NAME] = userName
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(SESSION_TOKEN)
            it.remove(USER_ID)
            it.remove(USER_NAME)
        }
    }

    private suspend fun saveDeviceId(id: String) {
        context.dataStore.edit { it[DEVICE_ID] = id }
    }
}
