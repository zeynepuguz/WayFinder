import { ApiRequestError, buildQuery, http, setUnauthorizedHandler, tokenStore } from './client'

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

  it('returns undefined for 204 responses', async () => {
    mockFetch(204, undefined)
    await expect(http.delete('/routes/1')).resolves.toBeUndefined()
  })
})
