// Travel guides for search engines, built only from verified place data (no invented facts).
// Turkish under /rehber/..., English for tourists under /en/... (with hreflang pairs).
// Plain static HTML (no app bundle): fast, crawlable, links into the app for planning.

const WALKING_M_PER_MIN = 80
// Streets are not straight lines
const DETOUR = 1.3

export const CATEGORY_EN = {
  BREAKFAST: { slug: 'breakfast', label: 'Breakfast', title: 'Breakfast in Kadıköy' },
  RESTAURANT: { slug: 'restaurants', label: 'Restaurant', title: 'Where to eat in Kadıköy' },
  CAFE: { slug: 'cafes', label: 'Café', title: 'Coffee and cafés in Kadıköy' },
  DESSERT: { slug: 'desserts', label: 'Dessert', title: 'Desserts and sweets in Kadıköy' },
  ATTRACTION: { slug: 'sights', label: 'Sights', title: 'Sights in Kadıköy' },
  MUSEUM: { slug: 'museums', label: 'Museum', title: 'Museums in Kadıköy' },
  PARK: { slug: 'parks', label: 'Park', title: 'Parks and seaside in Kadıköy' },
  CULTURE: { slug: 'culture', label: 'Culture', title: 'Culture and arts in Kadıköy' },
}

const DAYS_TR = ['Pazartesi', 'Salı', 'Çarşamba', 'Perşembe', 'Cuma', 'Cumartesi', 'Pazar']
const DAYS_EN = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

export const escape = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
const hhmm = t => String(t).slice(0, 5)

export function walkingMinutes(a, b) {
  const toRad = d => (d * Math.PI) / 180
  const dLat = toRad(b.latitude - a.latitude)
  const dLon = toRad(b.longitude - a.longitude)
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.latitude)) * Math.cos(toRad(b.latitude)) * Math.sin(dLon / 2) ** 2
  const meters = 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h)) * DETOUR
  return Math.max(1, Math.round(meters / WALKING_M_PER_MIN))
}

export function hoursTable(place, lang) {
  const days = lang === 'en' ? DAYS_EN : DAYS_TR
  if (!place.openingHours?.length) {
    return lang === 'en'
      ? '<p class="muted">Opening hours not verified; check before you go.</p>'
      : '<p class="muted">Çalışma saatleri doğrulanmadı; gitmeden önce kontrol et.</p>'
  }
  const rows = days.map((day, i) => {
    const hours = place.openingHours.filter(h => h.dayOfWeek === i + 1)
    const text = hours.length ? hours.map(h => `${hhmm(h.opensAt)}–${hhmm(h.closesAt)}`).join(', ') : (lang === 'en' ? 'Closed' : 'Kapalı')
    return `<tr><th scope="row">${day}</th><td>${text}</td></tr>`
  }).join('')
  return `<table class="hours"><caption>${lang === 'en' ? 'Opening hours' : 'Çalışma saatleri'}</caption>${rows}</table>`
}

function cost(place, lang) {
  if (!place.estimatedCost) return lang === 'en' ? 'Free' : 'Ücretsiz'
  return lang === 'en' ? `~${place.estimatedCost} TL per person (estimate)` : `kişi başı ~${place.estimatedCost} TL (tahmini)`
}

// A place card used by every guide
function card(place, { lang, categoryLabels, href, note }) {
  const description = lang === 'en' ? place.descriptionEn : place.description
  const meta = [categoryLabels[place.category], place.neighborhood, cost(place, lang),
    place.indoor ? (lang === 'en' ? 'indoor' : 'kapalı alan') : (lang === 'en' ? 'outdoor' : 'açık alan')]
    .filter(Boolean).map(escape).join(' · ')
  return `<article class="card">
  <h3><a href="${href(place)}">${escape(place.name)}</a></h3>
  <p class="meta">${meta}</p>
  ${description ? `<p>${escape(description)}</p>` : ''}
  ${note ? `<p class="note">${escape(note)}</p>` : ''}
</article>`
}

/** Greedy walkable day: one stop per slot, each next stop the best-rated among the closest candidates. */
export function sampleDay(places) {
  const slots = [
    { categories: ['BREAKFAST', 'CAFE'], tag: 'breakfast', tr: 'Kahvaltı', en: 'Breakfast' },
    { categories: ['ATTRACTION'], tr: 'Gezi', en: 'Sightseeing' },
    { categories: ['RESTAURANT'], tr: 'Öğle yemeği', en: 'Lunch' },
    { categories: ['MUSEUM', 'ATTRACTION', 'CULTURE'], tr: 'Kültür', en: 'Culture' },
    { categories: ['DESSERT', 'CAFE'], tr: 'Tatlı ve kahve', en: 'Dessert and coffee' },
    { categories: ['PARK'], tag: 'sea', tr: 'Gün batımı', en: 'Sunset' },
  ]
  const used = new Set()
  const stops = []
  for (const slot of slots) {
    let candidates = places.filter(p => slot.categories.includes(p.category) && !used.has(p.id))
    if (slot.tag && candidates.some(p => p.tags?.includes(slot.tag))) {
      candidates = candidates.filter(p => p.tags?.includes(slot.tag))
    }
    if (!candidates.length) continue
    const previous = stops.at(-1)?.place
    const scored = candidates
      .map(p => ({ p, minutes: previous ? walkingMinutes(previous, p) : 0 }))
      .sort((a, b) => a.minutes - b.minutes || (b.p.rating ?? 0) - (a.p.rating ?? 0))
    // Among the three closest, take the best rated
    const pick = scored.slice(0, 3).sort((a, b) => (b.p.rating ?? 0) - (a.p.rating ?? 0))[0]
    used.add(pick.p.id)
    stops.push({ slot, place: pick.p, walk: pick.minutes })
  }
  return stops
}

export function guides({ places, categoriesTr }) {
  const indoor = places.filter(p => p.indoor)
  const free = places.filter(p => !p.estimatedCost)
  const food = places.filter(p => ['BREAKFAST', 'CAFE', 'DESSERT'].includes(p.category))
  const byRating = list => [...list].sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0))
  const day = sampleDay(places)
  const trLabels = Object.fromEntries(Object.entries(categoriesTr).map(([k, v]) => [k, v.label]))
  const enLabels = Object.fromEntries(Object.entries(CATEGORY_EN).map(([k, v]) => [k, v.label]))
  const trHref = p => `/places/${p.id}`
  const enHref = p => `/en/places/${p.id}`

  const dayBody = (lang) => {
    const labels = lang === 'en' ? enLabels : trLabels
    const href = lang === 'en' ? enHref : trHref
    return `<ol class="timeline">${day.map((s, i) => `<li>
  <p class="step">${i + 1}. ${escape(lang === 'en' ? s.slot.en : s.slot.tr)}${i > 0 ? ` · ${lang === 'en' ? `~${s.walk} min walk` : `~${s.walk} dk yürüme`}` : ''}</p>
  ${card(s.place, { lang, categoryLabels: labels, href })}
</li>`).join('')}</ol>`
  }

  const list = (items, lang) => items.map(p => card(p, {
    lang, categoryLabels: lang === 'en' ? enLabels : trLabels, href: lang === 'en' ? enHref : trHref,
  })).join('')

  // Each guide exists in both languages; `pair` links them for hreflang
  return [
    {
      pair: 'overview',
      tr: {
        path: '/rehber/kadikoy-gezi-rehberi',
        title: 'Kadıköy gezi rehberi: gezilecek yerler, kahve ve yemek',
        description: `Kadıköy’de gezilecek yerler, kafeler, restoranlar ve parklar: ${places.length} mekan, konumları ve çalışma saatleriyle.`,
        intro: 'Kadıköy, İstanbul’un Anadolu yakasında çarşısı, sahili ve kafeleriyle gün boyu yürüyerek gezilebilecek bir semt. Aşağıdaki mekanlar Nomi’nin kontrol ettiği gerçek yerlerden oluşuyor.',
        body: Object.entries(categoriesTr).map(([c, v]) => {
          const items = byRating(places.filter(p => p.category === c))
          return items.length ? `<h2>${escape(v.title)}</h2>${list(items, 'tr')}` : ''
        }).join(''),
      },
      en: {
        path: '/en/guides/things-to-do-in-kadikoy',
        title: 'Things to do in Kadıköy, Istanbul: sights, cafés and food',
        description: `A local guide to Kadıköy on Istanbul’s Asian side: ${places.length} places to see, eat and drink, with locations and opening hours.`,
        intro: 'Kadıköy is a walkable neighbourhood on Istanbul’s Asian side, with a busy market, a seaside promenade and plenty of cafés. Every place below was checked by Nomi.',
        body: Object.entries(CATEGORY_EN).map(([c, v]) => {
          const items = byRating(places.filter(p => p.category === c))
          return items.length ? `<h2>${escape(v.title)}</h2>${list(items, 'en')}` : ''
        }).join(''),
      },
    },
    {
      pair: 'rainy',
      tr: {
        path: '/rehber/kadikoyde-yagmurlu-gunde-ne-yapilir',
        title: 'Kadıköy’de yağmurlu günde ne yapılır? Kapalı mekanlar',
        description: `Yağmurda Kadıköy: ${indoor.length} kapalı mekan; kafeler, müzeler, pastaneler ve pasajlar.`,
        intro: 'Yağmur başladığında Kadıköy’de sığınabileceğin kapalı mekanlar. Nomi, gezi sırasında “yağmur başladı” dediğinde rotanı bu tür yerlerle yeniden düzenler.',
        body: list(byRating(indoor), 'tr'),
      },
      en: {
        path: '/en/guides/kadikoy-rainy-day',
        title: 'Kadıköy on a rainy day: indoor things to do',
        description: `Rainy day in Kadıköy, Istanbul: ${indoor.length} indoor places, from cafés and museums to historic pastry shops.`,
        intro: 'Indoor places in Kadıköy for a rainy day. When you tell Nomi “it started raining” during a walk, it re-plans your route with places like these.',
        body: list(byRating(indoor), 'en'),
      },
    },
    {
      pair: 'free',
      tr: {
        path: '/rehber/kadikoyde-ucretsiz-gezilecek-yerler',
        title: 'Kadıköy’de ücretsiz gezilecek yerler',
        description: `Para harcamadan Kadıköy: ${free.length} ücretsiz yer; sahil, parklar, tarihi yapılar ve sokak sanatı.`,
        intro: 'Giriş ücreti olmayan, Kadıköy’de yürüyerek gezebileceğin yerler.',
        body: list(byRating(free), 'tr'),
      },
      en: {
        path: '/en/guides/free-things-to-do-in-kadikoy',
        title: 'Free things to do in Kadıköy, Istanbul',
        description: `Kadıköy on a budget: ${free.length} free places, including the seaside, parks, historic buildings and street art.`,
        intro: 'Places in Kadıköy with no entrance fee that you can explore on foot.',
        body: list(byRating(free), 'en'),
      },
    },
    {
      pair: 'day',
      tr: {
        path: '/rehber/kadikoy-1-gunluk-gezi-rotasi',
        title: 'Kadıköy 1 günlük gezi rotası (yürüyerek)',
        description: `Kadıköy’de bir gün: ${day.map(s => s.place.name).join(', ')}. Duraklar arası yürüme süreleriyle örnek rota.`,
        intro: 'Kahvaltıdan gün batımına kadar yürüyerek yapılabilecek örnek bir Kadıköy günü. Yürüme süreleri yaklaşıktır; saatleri gitmeden kontrol et. Nomi uygulaması bu rotayı bütçene, hava durumuna ve saatine göre senin için kişiselleştirir.',
        body: dayBody('tr'),
      },
      en: {
        path: '/en/guides/kadikoy-one-day-itinerary',
        title: 'One day in Kadıköy: a walking itinerary',
        description: `How to spend a day in Kadıköy, Istanbul: ${day.map(s => s.place.name).join(', ')}, with walking times between stops.`,
        intro: 'A sample day in Kadıköy on foot, from breakfast to sunset. Walking times are approximate; check opening hours before you go. The Nomi app adapts this route to your budget, the weather and the time of day.',
        body: dayBody('en'),
      },
    },
    {
      pair: 'coffee',
      tr: {
        path: '/rehber/kadikoyde-kahvalti-ve-kahve',
        title: 'Kadıköy’de kahvaltı, kahve ve tatlı',
        description: `Kadıköy’de kahvaltı, Türk kahvesi, nitelikli kahve ve tarihi pastaneler: ${food.length} mekan.`,
        intro: 'Kadıköy’de güne başlamak ya da mola vermek için kafeler, çay bahçeleri ve pastaneler.',
        body: list(byRating(food), 'tr'),
      },
      en: {
        path: '/en/guides/kadikoy-coffee-and-breakfast',
        title: 'Breakfast, coffee and desserts in Kadıköy',
        description: `Where to have breakfast, Turkish coffee, specialty coffee and sweets in Kadıköy: ${food.length} places.`,
        intro: 'Cafés, tea gardens and pastry shops in Kadıköy to start your day or take a break.',
        body: list(byRating(food), 'en'),
      },
    },
  ]
}

export { card as placeCard }
