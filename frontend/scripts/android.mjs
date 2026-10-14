// Builds the web app and copies it into the Android project.
//   node scripts/android.mjs dev      -> emulator build, API at http://10.0.2.2:8080 (.env.android-dev)
//   node scripts/android.mjs phone    -> a real phone on USB: API at http://localhost:8080 (.env.android-phone),
//                                        which "adb reverse" forwards to this computer; builds, installs and opens
//                                        the app on the phone (USB debugging on, the backend running here)
//   node scripts/android.mjs release  -> store build, API from .env.production (HTTPS)
import { execSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const target = process.argv[2] ?? 'dev'
const MODES = { dev: 'android-dev', phone: 'android-phone', release: 'production' }
if (!(target in MODES)) {
  console.error('Usage: node scripts/android.mjs <dev|phone|release>')
  process.exit(1)
}

const run = (command, env = {}, cwd = undefined) =>
  execSync(command, { stdio: 'inherit', cwd, env: { ...process.env, ...env } })

// adb from the Android SDK the project is built with (android/local.properties), else from PATH
function adb() {
  const props = join('android', 'local.properties')
  const sdk = existsSync(props) && readFileSync(props, 'utf8').match(/^sdk\.dir=(.*)$/m)?.[1]
    .replace(/\\:/g, ':').replace(/\\\\/g, '\\')
  const exe = sdk && join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb')
  return exe && existsSync(exe) ? `"${exe}"` : 'adb'
}

if (target === 'phone') {
  // Exactly one real phone (not the emulator) must be connected and allow USB debugging
  const devices = execSync(`${adb()} devices`).toString().split('\n').slice(1)
    .map(line => line.trim().split(/\s+/)).filter(([id, state]) => id && state)
  const phones = devices.filter(([id]) => !id.startsWith('emulator-'))
  if (phones.length !== 1 || phones[0][1] !== 'device') {
    console.error(phones.length === 0
      ? 'No phone found. Connect it with a USB cable, turn on USB debugging and allow this computer on the phone.'
      : phones[0]?.[1] === 'unauthorized'
        ? 'The phone has not allowed this computer yet: tap "Allow" in the USB debugging dialog on the phone.'
        : 'More than one phone is connected: keep only one.')
    process.exit(1)
  }
  process.env.ANDROID_SERIAL = phones[0][0]
}

run(`npx vite build --mode ${MODES[target]}`)
run('npx cap sync android', target === 'release' ? {} : { CAP_DEV_HTTP: '1' })

if (target === 'phone') {
  // The phone's localhost:8080 is this computer's backend, over the cable (no Wi-Fi or firewall setup)
  run(`${adb()} reverse tcp:8080 tcp:8080`)
  const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew'
  try {
    run(`${gradlew} installDebug`, {}, 'android')
  } catch {
    // The first build after changes often fails in a stale Gradle / Kotlin daemon ("Could not isolate
    // parameters ..."); a fresh daemon builds it
    console.log('\nInstall failed once, restarting Gradle and trying again...')
    run(`${gradlew} --stop`, {}, 'android')
    run(`${gradlew} installDebug`, {}, 'android')
  }
  run(`${adb()} shell am start -n com.nomi.app/.MainActivity`)
  console.log('\nNomi is open on the phone. After reconnecting the cable run again: adb reverse tcp:8080 tcp:8080')
} else {
  console.log(`\nAndroid project updated (${target}). Open it with: npx cap open android`)
}
