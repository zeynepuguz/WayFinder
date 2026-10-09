import * as Sentry from '@sentry/react'

// Error reports of the web app / Android app (loaded only when VITE_SENTRY_DSN is set, see main.tsx).
// Nothing personal: no IP or user, no session replay, no request or chat text; URLs lose their query strings
// (place searches, coordinates) and e-mail addresses are masked.
const EMAIL = /[\w.+-]+@[\w-]+(\.[\w-]+)+/g

export function maskEmails(text: string | undefined): string | undefined {
  return text?.replace(EMAIL, '[email]')
}

function stripQuery(url: string | undefined): string | undefined {
  return url?.split('?')[0]
}

export function initSentry(dsn: string) {
  Sentry.init({
    dsn,
    environment: import.meta.env.MODE,
    // Sentry v11: what the SDK may collect. No user, cookies, headers (the bearer token), bodies, query strings
    dataCollection: {
      userInfo: false,
      cookies: false,
      httpHeaders: false,
      httpBodies: [],
      urlQueryParams: false,
      stackFrameVariables: false,
    },
    tracesSampleRate: 0,
    beforeSend(event) {
      if (event.request) {
        event.request.url = stripQuery(event.request.url)
        delete event.request.query_string
        delete event.request.cookies
        delete event.request.headers
      }
      event.exception?.values?.forEach(value => { value.value = maskEmails(value.value) })
      event.message = maskEmails(event.message)
      return event
    },
    beforeBreadcrumb(breadcrumb) {
      // Request breadcrumbs keep the endpoint, not its query (coordinates, search text)
      if (breadcrumb.data?.url) breadcrumb.data.url = stripQuery(String(breadcrumb.data.url))
      breadcrumb.message = maskEmails(breadcrumb.message)
      return breadcrumb
    },
  })
}
