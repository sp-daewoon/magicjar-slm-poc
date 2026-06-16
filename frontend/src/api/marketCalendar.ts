import apiClient from '../lib/axios'

export interface MarketCalendarEvent {
  id: number
  date: string
  market: string
  eventType: string
  title: string
  instrumentCode: string | null
  description: string | null
  source: string
  createdAt: string
  updatedAt: string
}

export interface MarketCalendarDay {
  date: string
  isBusinessDay: boolean
  isHoliday: boolean
  holidayName: string | null
  source: string | null
  events: MarketCalendarEvent[]
}

export interface MarketCalendarResponse {
  from: string
  to: string
  days: MarketCalendarDay[]
}

export interface MarketCalendarEventRequest {
  date: string
  market: string
  eventType: string
  title: string
  instrumentCode?: string | null
  description?: string | null
}

export async function fetchMarketCalendar(params: {
  from: string
  to: string
}): Promise<MarketCalendarResponse> {
  const { data } = await apiClient.get<MarketCalendarResponse>('/market/calendar', { params })
  return data
}

export async function createMarketCalendarEvent(
  request: MarketCalendarEventRequest,
): Promise<MarketCalendarEvent> {
  const { data } = await apiClient.post<MarketCalendarEvent>('/market/calendar/events', request)
  return data
}

export async function updateMarketCalendarEvent(
  id: number,
  request: MarketCalendarEventRequest,
): Promise<MarketCalendarEvent> {
  const { data } = await apiClient.put<MarketCalendarEvent>(
    `/market/calendar/events/${id}`,
    request,
  )
  return data
}

export async function deleteMarketCalendarEvent(id: number): Promise<void> {
  await apiClient.delete(`/market/calendar/events/${id}`)
}
