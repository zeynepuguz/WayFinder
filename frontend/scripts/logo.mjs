// Builds every logo file from assets/logo-source.png (the wordmark on its flat orange background):
//   node scripts/logo.mjs
// then, for the Android icons and splash screens:  npx capacitor-assets generate --android
//
// The wordmark is cut out of the background once (alpha = how dark a pixel is), so it can be drawn in black,
// or in the logo's orange on a dark ground, at any size.
import sharp from 'sharp'

const SOURCE = 'assets/logo-source.png'
const DARK = { r: 11, g: 15, b: 23 }
const PAGE = { r: 247, g: 248, b: 250 }

const { data, info } = await sharp(SOURCE).removeAlpha().raw().toBuffer({ resolveWithObject: true })
const { width, height } = info
const ORANGE = { r: data[0], g: data[1], b: data[2] }
const luminance = (r, g, b) => 0.299 * r + 0.587 * g + 0.114 * b
const groundLuminance = luminance(ORANGE.r, ORANGE.g, ORANGE.b)

// Alpha mask of the mark and its bounding box
const alpha = Buffer.alloc(width * height)
let minX = width, minY = height, maxX = 0, maxY = 0
for (let y = 0; y < height; y++) {
  for (let x = 0; x < width; x++) {
    const i = (y * width + x) * 3
    // Black = opaque, the orange ground = transparent; in between (smooth edges) proportional
    const a = Math.max(0, Math.min(1, (groundLuminance - luminance(data[i], data[i + 1], data[i + 2])) / (groundLuminance - 40)))
    alpha[y * width + x] = Math.round(a * 255)
    if (a > 0.5) {
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
    }
  }
}
const box = { left: minX, top: minY, width: maxX - minX + 1, height: maxY - minY + 1 }

// The mark alone in one colour, as wide as asked
async function mark(color, targetWidth) {
  const rgba = Buffer.alloc(width * height * 4)
  for (let p = 0; p < width * height; p++) {
    rgba[p * 4] = color.r
    rgba[p * 4 + 1] = color.g
    rgba[p * 4 + 2] = color.b
    rgba[p * 4 + 3] = alpha[p]
  }
  return sharp(rgba, { raw: { width, height, channels: 4 } }).extract(box).resize({ width: Math.round(targetWidth) }).png().toBuffer()
}

const BLACK = { r: 0, g: 0, b: 0 }
const canvas = (w, h, background) => sharp({ create: { width: w, height: h, channels: 4, background } })

// The mark centred on a ground; share = its width as a part of the canvas width
async function centred(w, h, background, color, share, file) {
  await canvas(w, h, background).composite([{ input: await mark(color, w * share), gravity: 'centre' }]).png().toFile(file)
  console.log(file)
}

const CLEAR = { r: 0, g: 0, b: 0, alpha: 0 }
const solid = c => ({ ...c, alpha: 1 })

// App icon. Adaptive icons (Android 8+) are cut to a circle / squircle that only keeps the middle two thirds:
// the 2:1 wordmark fits that circle at 58 % of the width
await centred(1024, 1024, solid(ORANGE), BLACK, 0.8, 'assets/icon-only.png')
await centred(1024, 1024, CLEAR, BLACK, 0.58, 'assets/icon-foreground.png')
await canvas(1024, 1024, solid(ORANGE)).png().toFile('assets/icon-background.png')

// Splash screens: the logo as it is, and in orange on the app's dark ground
await centred(2732, 2732, solid(ORANGE), BLACK, 0.34, 'assets/splash.png')
await centred(2732, 2732, solid(DARK), ORANGE, 0.34, 'assets/splash-dark.png')

// Browser tab / home screen icon: rounded orange tile
const tile = Buffer.from('<svg width="192" height="192"><rect width="192" height="192" rx="42" ry="42"/></svg>')
const icon = await canvas(192, 192, solid(ORANGE)).composite([{ input: await mark(BLACK, 192 * 0.82), gravity: 'centre' }]).png().toBuffer()
await sharp(icon).composite([{ input: tile, blend: 'dest-in' }]).png().toFile('public/icon-192.png')
console.log('public/icon-192.png')

// In the app's header (components/visuals BrandMark): black, turned white by CSS in dark mode
await sharp(await mark(BLACK, 400)).toFile('src/assets/logo-wordmark.png')
console.log('src/assets/logo-wordmark.png')

// Share image (og:image): the wordmark replaces the brand row at the top left (with its soft shadow); the
// headline below stays. Read fully first, so the file can be written back in place
const patch = await canvas(460, 260, solid(PAGE)).png().toBuffer()
const og = await sharp('public/og-image.png').composite([
  { input: patch, left: 40, top: 20 },
  { input: await mark(BLACK, 300), left: 80, top: 62 },
]).png().toBuffer()
await sharp(og).toFile('public/og-image.png')
console.log('public/og-image.png')
