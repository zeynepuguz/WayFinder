import type { CapacitorConfig } from '@capacitor/cli'

// CAP_DEV_HTTP=1 (set by scripts/android.mjs dev): the emulator talks to the backend on the
// development machine over plain HTTP (http://10.0.2.2:8080). Release builds only use HTTPS.
const devHttp = process.env.CAP_DEV_HTTP === '1'

const config: CapacitorConfig = {
  // Play Store package name: cannot change after the first upload
  appId: 'com.nomi.app',
  appName: 'Nomi',
  webDir: 'dist',
  server: {
    androidScheme: devHttp ? 'http' : 'https',
    cleartext: devHttp,
  },
  android: {
    allowMixedContent: false,
  },
  plugins: {
    SplashScreen: {
      launchAutoHide: false,
      backgroundColor: '#F7F8FA',
      showSpinner: false,
    },
  },
}

export default config
