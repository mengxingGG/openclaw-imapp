package ai.openclaw.imapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.openclaw.imapp.ui.chat.ChatScreen
import ai.openclaw.imapp.ui.login.LoginScreen
import ai.openclaw.imapp.ui.login.LoginViewModel
import ai.openclaw.imapp.ui.settings.SettingsScreen
import ai.openclaw.imapp.ui.theme.ImappTheme
import ai.openclaw.imapp.service.ImappKeepAliveService
import ai.openclaw.imapp.data.api.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImappTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    
                    // 共享的 LoginViewModel
                    val loginViewModel: LoginViewModel = hiltViewModel()
                    
                    NavHost(navController = navController, startDestination = "splash") {
                        composable("splash") {
                            SplashScreen(
                                onNavigateToLogin = { navController.navigate("login") { popUpTo("splash") { inclusive = true } } },
                                onNavigateToChat = { navController.navigate("chat") { popUpTo("splash") { inclusive = true } } },
                            )
                        }
                        composable("login") {
                            LoginScreen(
                                onLoginSuccess = {
                                    ImappKeepAliveService.start(this@MainActivity)
                                    navController.navigate("chat") { popUpTo("login") { inclusive = true } }
                                },
                                viewModel = loginViewModel,
                            )
                        }
                        composable("chat") {
                            ChatScreen(
                                onNavigateToSettings = { navController.navigate("settings") },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onLogout = {
                                    ImappKeepAliveService.stop(this@MainActivity)
                                    navController.navigate("login") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerNetworkCallback()
    }

    override fun onPause() {
        super.onPause()
        unregisterNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let { cm.unregisterNetworkCallback(it) }
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 网络恢复，延迟一小段时间后触发重连（WebSocketManager 内部处理）
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000)
                    // 触发断开以便自动重连
                    // WebSocketManager 的 onFailure 已经有重连逻辑
                }
            }
            override fun onLost(network: Network) {
                // 网络丢失，WebSocket 会自动触发 onFailure → 重连
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
    }
}
