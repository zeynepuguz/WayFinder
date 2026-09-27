// Builds the web app and copies it into the Android project.
//   node scripts/android.mjs dev      -> emulator build, API at http://10.0.2.2:8080 (.env.android-dev)
//   node scripts/android.mjs release  -> store build, API from .env.production (HTTPS)
import { execSync } from 'node:child_process'

const target = process.argv[2] ?? 'dev'
if (!['dev', 'release'].includes(target)) {
  console.error('Usage: node scripts/android.mjs <dev|release>')
  process.exit(1)
}

const run = (command, env = {}) => execSync(command, { stdio: 'inherit', env: { ...process.env, ...env } })

run(`npx vite build --mode ${target === 'dev' ? 'android-dev' : 'production'}`)
run('npx cap sync android', target === 'dev' ? { CAP_DEV_HTTP: '1' } : {})

console.log(`\nAndroid project updated (${target}). Open it with: npx cap open android`)
