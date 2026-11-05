export default defineEventHandler((event) => {
  const config = useRuntimeConfig(event)
  if (!config.apiBaseServer) {
    throw createError({ statusCode: 503, statusMessage: 'API upstream is not configured' })
  }
  const request = getRequestURL(event)
  const suffix = request.pathname.slice('/api/v1'.length)
  const target = `${String(config.apiBaseServer).replace(/\/$/, '')}${suffix}${request.search}`
  return proxyRequest(event, target)
})
