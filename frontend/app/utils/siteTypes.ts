export type SiteSettings = { siteName: string; announcement?: string; pageSize: number; commentEnabled: boolean; noticeText?: string; copyright?: string; icpNumber?: string; policeNumber?: string }
export type AboutProfile = { content: string; updatedAt?: string; nickname?: string; avatar?: string; email?: string; github?: string; wechat?: string; qq?: string }
export type SiteItem = { id: number; title: string; description?: string; primaryUrl?: string; secondaryUrl?: string; metadata?: string; cover?: string; sort: number }
export const externalUrl = (value?: string) => !!value && /^https?:\/\//i.test(value)
export const hostOf = (value?: string) => { try { return new URL(value || '').hostname } catch { return '' } }
