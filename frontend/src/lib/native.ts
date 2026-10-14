import { App as CapacitorApp } from '@capacitor/app'
import { SplashScreen } from '@capacitor/splash-screen'
import { StatusBar, Style } from '@capacitor/status-bar'
import { isNativeApp } from './billing'
import { isDark, onThemeChange } from './theme'

// The status bar takes the app's paper colour (light) or navy black (dark)
async function paintStatusBar(dark: boolean) {
  try {
    await StatusBar.setStyle({ style: dark ? Style.Dark : Style.Light })
    await StatusBar.setBackgroundColor({ color: dark ? '#0B0F17' : '#FBF7F2' })
  } catch {
    // not available on every Android version
  }
}

// Android shell setup: status bar colors, splash screen, hardware back button
export async function setupNativeShell(goBack: () => boolean) {
  if (!isNativeApp()) return

  await paintStatusBar(isDark())
  onThemeChange(dark => void paintStatusBar(dark))

  // Back button: go back inside the app, leave the app from a root screen
  await CapacitorApp.addListener('backButton', () => {
    if (!goBack()) void CapacitorApp.exitApp()
  })

  await SplashScreen.hide()
}
