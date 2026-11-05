export default defineNuxtPlugin(nuxtApp => {
  const router = useRouter()
  const config = useRuntimeConfig()
  const token = useCookie<string | null>('blog-admin-token')
  let previous = document.referrer
  let lastPath = ''
  const record = () => {
    const path = router.currentRoute.value.path
    if (path === lastPath) return
    const referrer = previous
    previous = path
    lastPath = path
    if (!/^\/(?:articles|article\/[1-9][0-9]{0,17}|projects|favorites|links|about)?$/.test(path)) return
    $fetch(`${config.public.apiBase}/visits`, {
      method: 'POST', body: { path, referrer: referrer.slice(0, 1000) },
      headers: token.value ? { Authorization: `Bearer ${token.value}` } : undefined,
    }).catch(() => {})
  }
  nuxtApp.hook('page:finish', record)
  nuxtApp.hook('app:mounted', record)
})
