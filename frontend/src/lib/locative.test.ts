import { ablativeTr, googleMapsSearchUrl, locativeTr, popularRouteCost } from './format'

describe('locativeTr', () => {
  it.each([
    ['İstanbul', 'İstanbul’da'],
    ['Ankara', 'Ankara’da'],
    ['İzmir', 'İzmir’de'],
    ['Antep', 'Antep’te'],
    ['Gaziantep', 'Gaziantep’te'],
    ['Muş', 'Muş’ta'],
    ['Tokat', 'Tokat’ta'],
    ['Rize', 'Rize’de'],
    ['Kars', 'Kars’ta'],
    ['Van', 'Van’da'],
    ['Bolu', 'Bolu’da'],
    ['Düzce', 'Düzce’de'],
    ['Çankırı', 'Çankırı’da'],
    ['Iğdır', 'Iğdır’da'],
    ['Hakkari', 'Hakkari’de'],
    ['Kahramanmaraş', 'Kahramanmaraş’ta'],
    ['Eskişehir', 'Eskişehir’de'],
    ['Sinop', 'Sinop’ta'],
    ['Ordu', 'Ordu’da'],
    ['Kütahya', 'Kütahya’da'],
    ['Şırnak', 'Şırnak’ta'],
    ['Tunceli', 'Tunceli’de'],
    ['Bartın', 'Bartın’da'],
  ])('%s -> %s', (name, expected) => {
    expect(locativeTr(name)).toBe(expected)
  })
})

describe('popularRouteCost', () => {
  it('shows only known prices and says what is missing', () => {
    expect(popularRouteCost(null, 3)).toBe('Fiyat bilgisi yok')
    expect(popularRouteCost(0, 0)).toBe('Ücretsiz')
    expect(popularRouteCost(1250, 0)).toBe('Kişi başı ~1.250 TL')
    expect(popularRouteCost(450, 2)).toBe('Kişi başı ~450 TL · 2 durağın fiyatı bilinmiyor')
  })
})

describe('ablativeTr', () => {
  it.each([
    ['Kadıköy', 'Kadıköy’den'],
    ['Beşiktaş', 'Beşiktaş’tan'],
    ['Üsküdar', 'Üsküdar’dan'],
    ['Çankaya', 'Çankaya’dan'],
    ['Gaziantep', 'Gaziantep’ten'],
    ['Şişli', 'Şişli’den'],
    ['Bodrum', 'Bodrum’dan'],
    ['Eyüpsultan', 'Eyüpsultan’dan'],
    [' Fatih ', 'Fatih’ten'],
  ])('%s -> %s', (name, expected) => {
    expect(ablativeTr(name)).toBe(expected)
  })
})

describe('googleMapsSearchUrl', () => {
  const query = (url: string) => new URL(url).searchParams.get('query')

  it('searches a place with its district and city', () => {
    const url = googleMapsSearchUrl('Moda Kahve', 'Kadıköy', 'İstanbul')
    expect(url.startsWith('https://www.google.com/maps/search/?api=1&query=')).toBe(true)
    expect(query(url)).toBe('Moda Kahve, Kadıköy İstanbul')
  })

  it('leaves out unknown parts', () => {
    expect(query(googleMapsSearchUrl('Moda Kahve', null, 'İstanbul'))).toBe('Moda Kahve, İstanbul')
    expect(query(googleMapsSearchUrl('Moda Kahve', null, undefined))).toBe('Moda Kahve')
  })

  it('encodes special characters', () => {
    expect(googleMapsSearchUrl('A&B Cafe #1')).toContain('A%26B%20Cafe%20%231')
  })
})
