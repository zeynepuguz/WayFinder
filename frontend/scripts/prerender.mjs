// Builds crawlable HTML for search engines on top of the SPA build (dist/).
//
// Turkish (the app's own pages, content already inside #root so the app mounts over it):
//   /                      home with categories, guides and places linked
//   /places/{id}/          one page per place (title, description, address, hours, JSON-LD)
//   /kadikoy/{category}/   one page per category ("Kadıköy'de kahve ve kafeler" ...)
// Static travel guides (plain HTML, no app bundle), Turkish and English with hreflang pairs:
//   /rehber/...            Turkish guides
//   /en/, /en/guides/..., /en/places/{id}/, /en/kadikoy/{category}/   English for tourists
// Plus /sitemap.xml (with language alternates) and /robots.txt.
//
// Usage: node scripts/prerender.mjs --api http://backend:8080/api/v1 --site https://nomi.example.com
//                                   [--dist dist] [--out dist] [--wait 180]
import { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { CATEGORY_EN, escape, guides, hoursTable, placeCard } from './guides.mjs'

const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, arg, i, all) => {
  if (arg.startsWith('--')) pairs.push([arg.slice(2), all[i + 1]])
  return pairs
}, []))

const API = (args.api ?? process.env.PRERENDER_API_URL ?? '').replace(/\/$/, '')
const SITE = (args.site ?? process.env.SITE_URL ?? '').replace(/\/$/, '')
const DIST = args.dist ?? 'dist'
const OUT = args.out ?? DIST
const WAIT_SECONDS = Number(args.wait ?? 180)

if (!API || !SITE) {
  console.error('prerender: --api and --site are required')
  process.exit(1)
}

// Keep in sync with CATEGORY_SLUGS in src/lib/format.ts
const CATEGORIES = {
  BREAKFAST: { slug: 'kahvalti', label: 'Kahvaltı', title: 'Kadıköy’de kahvaltı', schema: 'Restaurant' },
  RESTAURANT: { slug: 'restoran', label: 'Restoran', title: 'Kadıköy’de nerede yenir', schema: 'Restaurant' },
  CAFE: { slug: 'kafe', label: 'Kafe', title: 'Kadıköy’de kahve ve kafeler', schema: 'CafeOrCoffeeShop' },
  DESSERT: { slug: 'tatli', label: 'Tatlı', title: 'Kadıköy’de tatlı', schema: 'FoodEstablishment' },
  ATTRACTION: { slug: 'gezilecek-yerler', label: 'Gezilecek yer', title: 'Kadıköy’de gezilecek yerler', schema: 'TouristAttraction' },
  MUSEUM: { slug: 'muze', label: 'Müze', title: 'Kadıköy’de müzeler', schema: 'Museum' },
  PARK: { slug: 'park', label: 'Park', title: 'Kadıköy’de parklar ve sahiller', schema: 'Park' },
  CULTURE: { slug: 'kultur', label: 'Kültür', title: 'Kadıköy’de kültür ve sanat', schema: 'TouristAttraction' },
}

const SCHEMA_DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
const hhmm = t => String(t).slice(0, 5)

async function fetchJson(path, lang = 'tr') {
  const response = await fetch(API + path, { headers: { 'Accept-Language': lang } })
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`)
  return response.json()
}

// The backend may still be starting when this runs (docker compose up)
async function waitForApi() {
  const deadline = Date.now() + WAIT_SECONDS * 1000
  for (;;) {
    try {
      await fetchJson('/places?size=1')
      return
    } catch (e) {
      if (Date.now() > deadline) throw new Error(`API not reachable at ${API}: ${e.message}`)
      await new Promise(r => setTimeout(r, 3000))
    }
  }
}

// Only hand-verified places get pages: thousands of OpenStreetMap entries without descriptions
// would be thin content for search engines. The app itself still shows all of them.
async function fetchAllPlaces(lang) {
  const places = []
  for (let page = 0; ; page++) {
    const result = await fetchJson(`/places?size=100&page=${page}&verified=true`, lang)
    places.push(...result.content)
    if (page + 1 >= result.totalPages) return places
  }
}

// ---------- shared head ----------

function head({ path, title, description, lang, alternates, jsonLd, image }) {
  const url = SITE + path
  return [
    `<title>${escape(title)}</title>`,
    `<meta name="description" content="${escape(description)}" />`,
    `<link rel="canonical" href="${escape(url)}" />`,
    ...(alternates ?? []).map(a => `<link rel="alternate" hreflang="${a.lang}" href="${escape(SITE + a.path)}" />`),
    ...(alternates?.length ? [`<link rel="alternate" hreflang="x-default" href="${escape(SITE + alternates.find(a => a.lang === 'tr').path)}" />`] : []),
    `<meta property="og:type" content="website" />`,
    `<meta property="og:site_name" content="Nomi" />`,
    `<meta property="og:locale" content="${lang === 'en' ? 'en_GB' : 'tr_TR'}" />`,
    `<meta property="og:title" content="${escape(title)}" />`,
    `<meta property="og:description" content="${escape(description)}" />`,
    `<meta property="og:url" content="${escape(url)}" />`,
    // A place page shares its own (free-licensed) photo; everything else the Nomi card
    `<meta property="og:image" content="${escape(image ?? `${SITE}/og-image.png`)}" />`,
    `<meta name="twitter:card" content="summary_large_image" />`,
    ...(jsonLd ? [`<script type="application/ld+json">${JSON.stringify(jsonLd).replace(/</g, '\\u003c')}</script>`] : []),
  ].join('\n    ')
}

// App page: the SPA's index.html with the content inside #root
function appPage(template, options) {
  return template
    .replace(/<title>[\s\S]*?<\/title>/, head({ ...options, lang: 'tr' }))
    .replace(/<meta name="description"[^>]*>/, '')
    .replace('<div id="root"></div>', `<div id="root"><main class="prerender">${options.body}</main></div>`)
}

// Static guide page: no app bundle, own stylesheet
function staticPage(options) {
  const en = options.lang === 'en'
  const other = options.alternates?.find(a => a.lang !== options.lang)
  const nav = en
    ? `<a href="/en/">Guides</a><a href="/?lang=en">Open the app</a>${other ? `<a href="${other.path}" hreflang="tr" lang="tr">Türkçe</a>` : ''}`
    : `<a href="/rehber/">Rehberler</a><a href="/">Uygulamayı aç</a>${other ? `<a href="${other.path}" hreflang="en" lang="en">English</a>` : ''}`
  const cta = en
    ? `<aside class="cta"><strong>Plan your own day with Nomi</strong><p>Tell Nomi your budget, how much you want to walk and what you like; it builds a route with real places and re-plans it when it rains or you get tired.</p><a class="button" href="/?lang=en">Open Nomi in English</a></aside>`
    : `<aside class="cta"><strong>Gününü Nomi planlasın</strong><p>Bütçeni, ne kadar yürümek istediğini ve neleri sevdiğini söyle; Nomi gerçek mekanlarla rota hazırlasın, yağmur yağınca ya da yorulunca yeniden düzenlesin.</p><a class="button" href="/">Nomi’yi aç</a></aside>`
  const footer = en
    ? `<a href="/iletisim">About &amp; contact</a><a href="/kosullar">Terms (Turkish)</a><a href="/gizlilik">Privacy (Turkish)</a>`
    : `<a href="/iletisim">Hakkımızda ve İletişim</a><a href="/kosullar">Kullanım Koşulları</a><a href="/gizlilik">Gizlilik Politikası</a>`

  return `<!doctype html>
<html lang="${en ? 'en' : 'tr'}">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <meta name="theme-color" content="#e8603c" />
    <link rel="icon" type="image/png" href="/icon-192.png" />
    <link rel="stylesheet" href="/guide.css" />
    ${head(options)}
  </head>
  <body>
    <header class="site"><a class="brand" href="${en ? '/en/' : '/'}"><img src="/icon-192.png" alt="" width="32" height="32" />Nomi</a><nav>${nav}</nav></header>
    <main>
      <h1>${escape(options.heading ?? options.title)}</h1>
      ${options.intro ? `<p class="lead">${escape(options.intro)}</p>` : ''}
      ${options.body}
      ${cta}
    </main>
    <footer class="site"><nav>${footer}</nav></footer>
  </body>
</html>
`
}

function placeJsonLd(place, lang) {
  const category = CATEGORIES[place.category]
  const description = lang === 'en' ? place.descriptionEn : place.description
  const data = {
    '@context': 'https://schema.org',
    '@type': category?.schema ?? 'Place',
    name: place.name,
    url: `${SITE}${lang === 'en' ? '/en' : ''}/places/${place.id}`,
    ...(description ? { description } : {}),
    address: {
      '@type': 'PostalAddress',
      ...(place.address ? { streetAddress: place.address } : {}),
      addressLocality: 'Kadıköy',
      addressRegion: 'İstanbul',
      addressCountry: 'TR',
    },
    geo: { '@type': 'GeoCoordinates', latitude: place.latitude, longitude: place.longitude },
    ...(place.image?.url ? { image: place.image.url } : {}),
  }
  // Only hours backed by a source are stored; never invent them here
  if (place.openingHours?.length) {
    data.openingHoursSpecification = place.openingHours.map(h => ({
      '@type': 'OpeningHoursSpecification',
      dayOfWeek: SCHEMA_DAYS[h.dayOfWeek - 1],
      opens: hhmm(h.opensAt),
      closes: hhmm(h.closesAt),
    }))
  }
  return data
}

function placeLink(place, lang) {
  const label = lang === 'en' ? CATEGORY_EN[place.category]?.label : CATEGORIES[place.category]?.label
  const href = lang === 'en' ? `/en/places/${place.id}` : `/places/${place.id}`
  return `<li><a href="${href}">${escape(place.name)}</a> · ${escape(label ?? '')}${place.neighborhood ? ` · ${escape(place.neighborhood)}` : ''}</li>`
}

function write(path, html) {
  if (path === '/') {
    writeFileSync(join(OUT, 'index.html'), html)
    return
  }
  const dir = join(OUT, path)
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'index.html'), html)
}

async function main() {
  if (!existsSync(join(DIST, 'index.html'))) throw new Error(`${DIST}/index.html not found; run vite build first`)
  if (OUT !== DIST) cpSync(DIST, OUT, { recursive: true })
  const template = readFileSync(join(DIST, 'index.html'), 'utf8')
  // Plain app shell for every other path (/assistant, /login, new places...): no home content, no canonical
  writeFileSync(join(OUT, 'app.html'), template)

  await waitForApi()
  const english = new Map((await fetchAllPlaces('en')).map(p => [p.id, p.description]))
  const places = (await fetchAllPlaces('tr'))
    .map(p => {
      const en = english.get(p.id)
      // Never put Turkish text on an English page: without a translation the description is left out
      return { ...p, descriptionEn: en && en !== p.description ? en : null }
    })
    .sort((a, b) => a.name.localeCompare(b.name, 'tr'))
  const byCategory = Object.fromEntries(Object.keys(CATEGORIES).map(c => [c, places.filter(p => p.category === c)]))
  const guideList = guides({ places, categoriesTr: CATEGORIES })

  // url -> alternates, for the sitemap
  const urls = []
  const add = (path, alternates) => urls.push({ path, alternates })

  // ---------- Turkish app pages ----------
  const homeAlternates = [{ lang: 'tr', path: '/' }, { lang: 'en', path: '/en/' }]
  const categoryLinks = Object.entries(CATEGORIES).filter(([c]) => byCategory[c].length)
    .map(([c, v]) => `<li><a href="/kadikoy/${v.slug}">${escape(v.title)}</a> (${byCategory[c].length})</li>`).join('')
  const guideLinksTr = guideList.map(g => `<li><a href="${g.tr.path}">${escape(g.tr.title)}</a></li>`).join('')
  write('/', appPage(template, {
    path: '/',
    alternates: homeAlternates,
    title: 'Nomi · Kadıköy’de gününü planlayan şehir asistanı',
    description: 'Kadıköy’de nerede kahvaltı yapılır, hangi kafeye gidilir, yağmurda nereye? Nomi konumuna, bütçene ve hava durumuna göre gerçek mekanlarla günlük rota planlar.',
    jsonLd: {
      '@context': 'https://schema.org',
      '@type': 'WebApplication',
      name: 'Nomi',
      url: SITE + '/',
      applicationCategory: 'TravelApplication',
      operatingSystem: 'Web, Android',
      inLanguage: ['tr', 'en'],
      description: 'Kadıköy için kişisel şehir asistanı: bütçe, hava durumu ve yürüme mesafesine göre günlük rota.',
    },
    body: `<h1>Nomi: Kadıköy’de gününü planlayan şehir asistanı</h1>
<p>Nomi, bulunduğun yere, bütçene, hava durumuna ve ne kadar yürümek istediğine göre Kadıköy’de gerçek mekanlarla günlük rota hazırlar. Gezerken “çok yorulduk” ya da “yağmur başladı” dersen rotanı hemen yeniden düzenler.</p>
<p><a href="/en/" hreflang="en" lang="en">Visiting Istanbul? Kadıköy guide in English</a></p>
<h2>Kadıköy rehberleri</h2><ul>${guideLinksTr}</ul>
<h2>Kategoriler</h2><ul>${categoryLinks}</ul>
<h2>Mekanlar</h2><ul>${places.map(p => placeLink(p, 'tr')).join('')}</ul>`,
  }))
  add('/', homeAlternates)

  for (const [category, info] of Object.entries(CATEGORIES)) {
    const list = byCategory[category]
    if (!list.length) continue
    const path = `/kadikoy/${info.slug}`
    const alternates = [{ lang: 'tr', path }, { lang: 'en', path: `/en/kadikoy/${CATEGORY_EN[category].slug}` }]
    write(path, appPage(template, {
      path,
      alternates,
      title: `${info.title} · Nomi`,
      description: `${info.title}: ${list.slice(0, 4).map(p => p.name).join(', ')} ve daha fazlası. Konumu, çalışma saatleri ve Nomi ile rota.`,
      jsonLd: {
        '@context': 'https://schema.org',
        '@type': 'ItemList',
        name: info.title,
        itemListElement: list.map((p, i) => ({ '@type': 'ListItem', position: i + 1, url: `${SITE}/places/${p.id}`, name: p.name })),
      },
      body: `<h1>${escape(info.title)}</h1><ul>${list.map(p => placeLink(p, 'tr')).join('')}</ul>
<p><a href="/">Nomi ile Kadıköy’de gününü planla</a></p>`,
    }))
    add(path, alternates)
  }

  for (const place of places) {
    const path = `/places/${place.id}`
    const alternates = [{ lang: 'tr', path }, { lang: 'en', path: `/en/places/${place.id}` }]
    const category = CATEGORIES[place.category]
    const description = [place.description, place.address && `Adres: ${place.address}, Kadıköy.`]
      .filter(Boolean).join(' ') || `${place.name}, Kadıköy`
    write(path, appPage(template, {
      path,
      alternates,
      title: `${place.name} · ${category?.label ?? 'Mekan'}, Kadıköy · Nomi`,
      description: description.slice(0, 300),
      jsonLd: placeJsonLd(place, 'tr'),
      image: place.image?.url,
      body: `<h1>${escape(place.name)}</h1>
<p>${escape(category?.label ?? '')}${place.neighborhood ? ` · ${escape(place.neighborhood)}` : ''} · Kadıköy</p>
${place.description ? `<p>${escape(place.description)}</p>` : ''}
${place.address ? `<p>Adres: ${escape(place.address)}</p>` : ''}
${hoursTable(place, 'tr')}
<p><a href="https://www.openstreetmap.org/?mlat=${place.latitude}&amp;mlon=${place.longitude}#map=18/${place.latitude}/${place.longitude}">Haritada gör</a></p>
${category ? `<p><a href="/kadikoy/${category.slug}">${escape(category.title)}</a></p>` : ''}`,
    }))
    add(path, alternates)
  }

  // ---------- Guides (static, both languages) ----------
  for (const guide of guideList) {
    const alternates = [{ lang: 'tr', path: guide.tr.path }, { lang: 'en', path: guide.en.path }]
    for (const lang of ['tr', 'en']) {
      const g = guide[lang]
      write(g.path, staticPage({ ...g, lang, alternates }))
      add(g.path, alternates)
    }
  }

  const guidesIndexAlternates = [{ lang: 'tr', path: '/rehber' }, { lang: 'en', path: '/en/' }]
  write('/rehber', staticPage({
    lang: 'tr',
    path: '/rehber',
    alternates: guidesIndexAlternates,
    title: 'Kadıköy gezi rehberleri · Nomi',
    heading: 'Kadıköy gezi rehberleri',
    description: 'Kadıköy’de gezilecek yerler, yağmurlu gün planı, ücretsiz yerler, 1 günlük rota ve kahve rehberi.',
    intro: 'Nomi’nin kontrol ettiği gerçek mekanlardan hazırlanan Kadıköy rehberleri.',
    body: `<ul class="guides">${guideList.map(g => `<li><a href="${g.tr.path}">${escape(g.tr.title)}</a><p>${escape(g.tr.description)}</p></li>`).join('')}</ul>`,
  }))
  add('/rehber', guidesIndexAlternates)

  // ---------- English for tourists ----------
  write('/en', staticPage({
    lang: 'en',
    path: '/en/',
    alternates: homeAlternates,
    title: 'Nomi · Kadıköy, Istanbul travel guide and day planner',
    heading: 'Kadıköy travel guide',
    description: 'Things to do in Kadıköy on Istanbul’s Asian side: sights, cafés, food, a one-day walking itinerary and rainy-day ideas, all with verified places.',
    intro: 'Kadıköy is the lively heart of Istanbul’s Asian side, a short ferry ride from the historic peninsula. These guides only list places Nomi has checked. Use the Nomi app in English to plan a day around your budget and the weather.',
    jsonLd: {
      '@context': 'https://schema.org',
      '@type': 'WebSite',
      name: 'Nomi',
      url: `${SITE}/en/`,
      inLanguage: 'en',
    },
    body: `<ul class="guides">${guideList.map(g => `<li><a href="${g.en.path}">${escape(g.en.title)}</a><p>${escape(g.en.description)}</p></li>`).join('')}</ul>
<h2>By category</h2><ul>${Object.entries(CATEGORY_EN).filter(([c]) => byCategory[c].length)
      .map(([c, v]) => `<li><a href="/en/kadikoy/${v.slug}">${escape(v.title)}</a> (${byCategory[c].length})</li>`).join('')}</ul>`,
  }))
  add('/en/', homeAlternates)

  for (const [category, info] of Object.entries(CATEGORY_EN)) {
    const list = byCategory[category]
    if (!list.length) continue
    const path = `/en/kadikoy/${info.slug}`
    const alternates = [{ lang: 'tr', path: `/kadikoy/${CATEGORIES[category].slug}` }, { lang: 'en', path }]
    write(path, staticPage({
      lang: 'en',
      path,
      alternates,
      title: `${info.title} · Nomi`,
      description: `${info.title}, Istanbul: ${list.slice(0, 4).map(p => p.name).join(', ')} and more, with locations and opening hours.`,
      body: list.map(p => placeCard(p, { lang: 'en', categoryLabels: Object.fromEntries(Object.entries(CATEGORY_EN).map(([k, v]) => [k, v.label])), href: x => `/en/places/${x.id}` })).join(''),
    }))
    add(path, alternates)
  }

  for (const place of places) {
    const path = `/en/places/${place.id}`
    const alternates = [{ lang: 'tr', path: `/places/${place.id}` }, { lang: 'en', path }]
    const category = CATEGORY_EN[place.category]
    write(path, staticPage({
      lang: 'en',
      path,
      alternates,
      title: `${place.name} · ${category?.label ?? 'Place'} in Kadıköy, Istanbul · Nomi`,
      heading: place.name,
      description: [place.descriptionEn, place.address && `Address: ${place.address}, Kadıköy, Istanbul.`]
        .filter(Boolean).join(' ').slice(0, 300) || `${place.name}, Kadıköy, Istanbul`,
      jsonLd: placeJsonLd(place, 'en'),
      image: place.image?.url,
      body: `<p class="meta">${escape(category?.label ?? '')}${place.neighborhood ? ` · ${escape(place.neighborhood)}` : ''} · Kadıköy, Istanbul</p>
${place.descriptionEn ? `<p>${escape(place.descriptionEn)}</p>` : ''}
${place.address ? `<p>Address: ${escape(place.address)}</p>` : ''}
<p>${place.estimatedCost ? `About ${place.estimatedCost} TL per person (estimate)` : 'Free'}${place.indoor ? ' · indoor' : ' · outdoor'}</p>
${hoursTable(place, 'en')}
<p><a href="https://www.openstreetmap.org/?mlat=${place.latitude}&amp;mlon=${place.longitude}#map=18/${place.latitude}/${place.longitude}">View on the map</a>${category ? ` · <a href="/en/kadikoy/${category.slug}">${escape(category.title)}</a>` : ''}</p>`,
    }))
    add(path, alternates)
  }

  // ---------- sitemap and robots ----------
  const today = new Date().toISOString().slice(0, 10)
  writeFileSync(join(OUT, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">
${urls.map(u => `  <url>
    <loc>${escape(SITE + u.path)}</loc>
    <lastmod>${today}</lastmod>
${(u.alternates ?? []).map(a => `    <xhtml:link rel="alternate" hreflang="${a.lang}" href="${escape(SITE + a.path)}" />`).join('\n')}
  </url>`).join('\n')}
</urlset>
`)
  writeFileSync(join(OUT, 'robots.txt'), `User-agent: *
Allow: /
Disallow: /api/
Disallow: /assistant
Disallow: /routes
Disallow: /saved
Disallow: /profile
Disallow: /premium
Disallow: /login
Disallow: /forgot-password
Disallow: /welcome

Sitemap: ${SITE}/sitemap.xml
`)
  const translated = places.filter(p => p.descriptionEn).length
  console.log(`prerender: ${urls.length} pages for ${places.length} places (${translated} with English descriptions) → ${OUT}`)
}

main().catch(e => {
  console.error('prerender failed:', e.message)
  process.exit(1)
})
