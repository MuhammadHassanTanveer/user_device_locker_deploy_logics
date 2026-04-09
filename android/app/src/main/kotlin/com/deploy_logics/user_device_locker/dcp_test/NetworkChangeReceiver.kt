package com.deploy_logics.user_device_locker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver that monitors network connectivity changes.
 * When internet becomes available, it syncs any pending API calls.
 */
class NetworkChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkChangeReceiver"
        
        private var isCallbackRegistered = false
        private var networkCallback: ConnectivityManager.NetworkCallback? = null

        /**
         * Register network callback for API 24+ (more efficient than broadcast)
         */
        fun registerNetworkCallback(context: Context) {
            if (isCallbackRegistered) return
            
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    networkCallback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            Log.d(TAG, "📶 Network became available")
                            syncPendingData(context)
                        }
                        
                        override fun onLost(network: Network) {
                            Log.d(TAG, "📴 Network lost")
                        }
                    }
                    
                    val request = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    
                    connectivityManager.registerNetworkCallback(request, networkCallback!!)
                    isCallbackRegistered = true
                    Log.d(TAG, "✅ Network callback registered")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error registering network callback: ${e.message}")
            }
        }

        /**
         * Unregister network callback
         */
        fun unregisterNetworkCallback(context: Context) {
            if (!isCallbackRegistered || networkCallback == null) return
            
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    connectivityManager.unregisterNetworkCallback(networkCallback!!)
                    isCallbackRegistered = false
                    networkCallback = null
                    Log.d(TAG, "✅ Network callback unregistered")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error unregistering network callback: ${e.message}")
            }
        }

        /**
         * Sync all pending data when network is available
         */
        private fun syncPendingData(context: Context) {
            Log.d(TAG, "🔄 Checking for pending data to sync...")
            
            // Sync pending device status
            DeviceStatusApi.syncPendingStatus(context)
            
            // Sync pending uninstall status
            if (CustomerStatusApi.hasPendingUninstallSync(context)) {
                Log.d(TAG, "📤 Found pending uninstall status - syncing...")
                CustomerStatusApi.syncPendingUninstallStatus(context) { success ->
                    if (success) {
                        Log.d(TAG, "✅ Pending uninstall status synced")
                    } else {
                        Log.w(TAG, "⚠️ Pending uninstall status sync failed")
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
            Log.d(TAG, "📡 Connectivity change received")
            
            // Check if we now have internet
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            val hasInternet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } else {
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo?.isConnected == true
            }
            
            if (hasInternet) {
                Log.d(TAG, "📶 Internet is now available")
                syncPendingData(context)
            } else {
                Log.d(TAG, "📴 No internet connection")
            }
        }
    }
}

