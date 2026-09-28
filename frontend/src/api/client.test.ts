import { ApiRequestError, buildQuery, http, mediaUrl, setUnauthorizedHandler, tokenStore } from './client'
import { api } from './index'

function mockFetch(status: number, body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
  tokenStore.set(null)
})

describe('api client', () => {
  it('builds query strings without empty values', () => {
    expect(buildQuery({ lat: 40.99, q: '', type: undefined, saved: false })).toBe('?lat=40.99&saved=false')
    expect(buildQuery({})).toBe('')
  })

  it('sends the bearer token and JSON body', async () => {
    tokenStore.set('abc')
    const fetchMock = mockFetch(200, { ok: true })

    await http.post('/routes', { latitude: 1 })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/routes')
    expect(init.headers.Authorization).toBe('Bearer abc')
    expect(init.body).toBe('{"latitude":1}')
  })

  it('turns backend errors into ApiRequestError with field errors', async () => {
    mockFetch(400, { status: 400, message: 'Validation failed', errors: { latitude: 'must not be null' } })

    const error = await http.get('/places').catch((e: ApiRequestError) => e) as ApiRequestError

    expect(error).toBeInstanceOf(ApiRequestError)
    expect(error.status).toBe(400)
    expect(error.errors.latitude).toBe('must not be null')
  })

  it('logs the user out when a saved token is rejected', async () => {
    tokenStore.set('expired')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    mockFetch(401, { status: 401, message: 'Authentication required', errors: {} })

    await http.get('/users/me').catch(() => {})

    expect(onUnauthorized).toHaveBeenCalled()
  })

  it('explains a proxy 502 (backend down / restarting) instead of showing "Bad Gateway"', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response('<html>Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway' })))

    const error = await http.get('/places').catch((e: ApiRequestError) => e) as ApiRequestError

    expect(error.status).toBe(502)
    expect(error.message).toBe('Sunucuya şu an ulaşılamıyor, birazdan tekrar dene')
  })

  it('returns undefined for 204 responses', async () => {
    mockFetch(204, undefined)
    await expect(http.delete('/routes/1')).resolves.toBeUndefined()
  })
})

describe('mediaUrl', () => {
  it('keeps backend paths on the same origin for the web app', () => {
    expect(mediaUrl('/media/photos/1_t.jpg', '/api/v1')).toBe('/media/photos/1_t.jpg')
  })

  it('uses the API host for the Android app', () => {
    expect(mediaUrl('/media/photos/1.jpg', 'https://api.example.com/api/v1')).toBe('https://api.example.com/media/photos/1.jpg')
    expect(mediaUrl('/media/photos/1.jpg', 'http://10.0.2.2:8080/api/v1')).toBe('http://10.0.2.2:8080/media/photos/1.jpg')
  })

  it('passes absolute http(s) URLs through and refuses anything else', () => {
    expect(mediaUrl('https://cdn.example.com/a.jpg', '/api/v1')).toBe('https://cdn.example.com/a.jpg')
    expect(mediaUrl('javascript:alert(1)', '/api/v1')).toBeNull()
    expect(mediaUrl('//evil.example.com/a.jpg', '/api/v1')).toBeNull()
    expect(mediaUrl(null)).toBeNull()
  })
})

describe('photo upload', () => {
  it('sends multipart FormData with the position and no JSON content type', async () => {
    tokenStore.set('abc')
    const fetchMock = mockFetch(202, { id: 7, status: 'PENDING' })
    const file = new File(['jpeg'], 'a.jpg', { type: 'image/jpeg' })

    const result = await api.uploadPhoto({ type: 'PLACE', id: 5 }, file, { latitude: 40.98, longitude: 29.02, accuracy: 15 })

    expect(result).toEqual({ id: 7, status: 'PENDING' })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/places/5/photos')
    expect(init.method).toBe('POST')
    expect(init.headers['Content-Type']).toBeUndefined()
    expect(init.headers.Authorization).toBe('Bearer abc')
    const form = init.body as FormData
    expect(form).toBeInstanceOf(FormData)
    expect(form.get('file')).toBeInstanceOf(File)
    expect(form.get('latitude')).toBe('40.98')
    expect(form.get('longitude')).toBe('29.02')
    expect(form.get('accuracy')).toBe('15')
  })

  it('leaves the position out when unknown and targets the district endpoint', async () => {
    const fetchMock = mockFetch(202, { id: 8, status: 'PENDING' })
    await api.uploadPhoto({ type: 'DISTRICT', city: 'istanbul', district: 'kadikoy' }, new File(['x'], 'b.png', { type: 'image/png' }), null)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/cities/istanbul/districts/kadikoy/photos')
    expect((init.body as FormData).has('latitude')).toBe(false)
  })
})
