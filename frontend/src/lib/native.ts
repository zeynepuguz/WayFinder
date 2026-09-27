import { App as CapacitorApp } from '@capacitor/app'
import { SplashScreen } from '@capacitor/splash-screen'
import { StatusBar, Style } from '@capacitor/status-bar'
import { isNativeApp } from './billing'

// Android shell setup: status bar colors, splash screen, hardware back button
export async function setupNativeShell(goBack: () => boolean) {
  if (!isNativeApp()) return

  const dark = window.matchMedia('(prefers-color-scheme: dark)').matches
  try {
    await StatusBar.setStyle({ style: dark ? Style.Dark : Style.Light })
    await StatusBar.setBackgroundColor({ color: dark ? '#0B0F17' : '#F7F8FA' })
  } catch {
    // not available on every Android version
  }

  // Back button: go back inside the app, leave the app from a root screen
  await CapacitorApp.addListener('backButton', () => {
    if (!goBack()) void CapacitorApp.exitApp()
  })

  await SplashScreen.hide()
}
