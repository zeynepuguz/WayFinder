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

// What the hardware back button does now: the latest handler wins, so callers may pass a new one on every render
let goBackHandler: () => boolean = () => false
let started = false

/**
 * Android shell setup: status bar colors, splash screen, hardware back button. The setup and the back button
 * listener happen once per app run; calling it again only replaces the back handler. (Each extra listener used to
 * add one more step back per press, landing on Ana Sayfa or leaving the app.)
 */
export async function setupNativeShell(goBack: () => boolean) {
  goBackHandler = goBack
  if (!isNativeApp() || started) return
  started = true

  await paintStatusBar(isDark())
  onThemeChange(dark => void paintStatusBar(dark))

  // Back button: go back inside the app, leave the app from a root screen
  await CapacitorApp.addListener('backButton', () => {
    if (!goBackHandler()) void CapacitorApp.exitApp()
  })

  await SplashScreen.hide()
}

// Tests start with a fresh shell
export function resetNativeShell() {
  started = false
  goBackHandler = () => false
}
