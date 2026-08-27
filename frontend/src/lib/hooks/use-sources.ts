import { useQuery, useMutation, useQueryClient, useInfiniteQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { sourcesApi } from '@/lib/api/sources'
import { QUERY_KEYS } from '@/lib/api/query-client'
import { useToast } from '@/lib/hooks/use-toast'
import { useTranslation } from '@/lib/hooks/use-translation'
import { getApiErrorMessage } from '@/lib/utils/error-handler'
import {
  CreateSourceRequest,
  UpdateSourceRequest,
  SourceResponse,
  SourceStatusResponse,
  SourceListResponse
} from '@/lib/types/api'

const NOTEBOOK_SOURCES_PAGE_SIZE = 30

export function useSources(notebookId?: string) {
  return useQuery({
    queryKey: QUERY_KEYS.sources(notebookId),
    queryFn: () => sourcesApi.list({ notebook_id: notebookId }),
    enabled: !!notebookId,
    staleTime: 5 * 1000, // 5 seconds - more responsive for real-time source updates
    refetchOnWindowFocus: true, // Refetch when user comes back to the tab
  })
}

/**
 * Hook for fetching notebook sources with infinite scroll pagination.
 * Returns flattened sources array and pagination controls.
 */
export function useNotebookSources(notebookId: string) {
  const queryClient = useQueryClient()

  const query = useInfiniteQuery({
    queryKey: QUERY_KEYS.sourcesInfinite(notebookId),
    queryFn: async ({ pageParam = 0 }) => {
      const data = await sourcesApi.list({
        notebook_id: notebookId,
        limit: NOTEBOOK_SOURCES_PAGE_SIZE,
        offset: pageParam,
        sort_by: 'updated',
        sort_order: 'desc',
      })
      return {
        sources: data,
        nextOffset: data.length === NOTEBOOK_SOURCES_PAGE_SIZE ? pageParam + data.length : undefined,
      }
    },
    initialPageParam: 0,
    getNextPageParam: (lastPage) => lastPage.nextOffset,
    enabled: !!notebookId,
    staleTime: 5 * 1000,
    refetchOnWindowFocus: true,
  })

  // Flatten all pages into a single array (memoized to prevent infinite re-renders)
  const sources: SourceListResponse[] = useMemo(
    () => query.data?.pages.flatMap(page => page.sources) ?? [],
    [query.data?.pages]
  )

  // Refetch function that resets to first page
  const refetch = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: QUERY_KEYS.sourcesInfinite(notebookId) })
  }, [queryClient, notebookId])

  return {
    sources,
    isLoading: query.isLoading,
    isFetchingNextPage: query.isFetchingNextPage,
    hasNextPage: query.hasNextPage,
    fetchNextPage: query.fetchNextPage,
    refetch,
    error: query.error,
  }
}

export function useSource(id: string) {
  return useQuery({
    queryKey: QUERY_KEYS.source(id),
    queryFn: () => sourcesApi.get(id),
    enabled: !!id,
    staleTime: 30 * 1000, // 30 seconds - shorter stale time for more responsive updates
    refetchOnWindowFocus: true, // Refetch when user comes back to the tab
  })
}

export function useCreateSource() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { t } = useTranslation()

  return useMutation({
    mutationFn: (data: CreateSourceRequest) => sourcesApi.create(data),
    onSuccess: (result: SourceResponse, variables) => {
      // Invalidate queries for all relevant notebooks with immediate refetch
      if (variables.notebooks) {
        variables.notebooks.forEach(notebookId => {
          queryClient.invalidateQueries({
            queryKey: QUERY_KEYS.sources(notebookId),
            refetchType: 'active'
          })
          queryClient.invalidateQueries({
            queryKey: QUERY_KEYS.sourcesInfinite(notebookId),
            refetchType: 'active'
          })
        })
      } else if (variables.notebook_id) {
        queryClient.invalidateQueries({
          queryKey: QUERY_KEYS.sources(variables.notebook_id),
          refetchType: 'active'
        })
        queryClient.invalidateQueries({
          queryKey: QUERY_KEYS.sourcesInfinite(variables.notebook_id),
          refetchType: 'active'
        })
      }

      // Invalidate general sources query too with immediate refetch
      queryClient.invalidateQueries({
        queryKey: QUERY_KEYS.sources(),
        refetchType: 'active'
      })

      // Show different messages based on processing mode
      if (variables.async_processing) {
        toast({
          title: t('sources.sourceQueued'),
          description: t('sources.sourceQueuedDesc'),
        })
      } else {
        toast({
          title: t('common.success'),
          description: t('sources.sourceAddedSuccess'),
        })
      }
    },
    onError: (error: unknown) => {
      toast({
        title: t('common.error'),
        description: getApiErrorMessage(error, (key) => t(key), t('sources.failedToAddSource')),
        variant: 'destructive',
      })
    },
  })
}

export function useUpdateSource() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { t } = useTranslation()

  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateSourceRequest }) =>
      sourcesApi.update(id, data),
    onSuccess: (_, { id }) => {
      // Invalidate ALL sources queries (both general and notebook-specific)
      queryClient.invalidateQueries({ queryKey: ['sources'] })
      queryClient.invalidateQueries({ queryKey: QUERY_KEYS.source(id) })
      toast({
        title: t('common.success'),
        description: t('sources.sourceUpdatedSuccess'),
      })
    },
    onError: (error: unknown) => {
      toast({
        title: t('common.error'),
        description: getApiErrorMessage(error, (key) => t(key), t('sources.failedToUpdateSource')),
        variant: 'destructive',
      })
    },
  })
}

export function useDeleteSource() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { t } = useTranslation()

  return useMutation({
    mutationFn: (id: string) => sourcesApi.delete(id),
    onSuccess: (_, id) => {
      // Invalidate ALL sources queries (both general and notebook-specific)
      queryClient.invalidateQueries({ queryKey: ['sources'] })
      // Also invalidate the specific source
      queryClient.invalidateQueries({ queryKey: QUERY_KEYS.source(id) })
      toast({
        title: t('common.success'),
        description: t('sources.sourceDeletedSuccess'),
      })
    },
    onError: (error: unknown) => {
      toast({
        title: t('common.error'),
        description: getApiErrorMessage(error, (key) => t(key), t('sources.failedToDeleteSource')),
        variant: 'destructive',
      })
    },
  })
}

export function useFileUpload() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { t } = useTranslation()

  return useMutation({
    mutationFn: ({ file, notebookId }: { file: File; notebookId: string }) =>
      sourcesApi.upload(file, notebookId),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: QUERY_KEYS.sources(variables.notebookId)
      })
      queryClient.invalidateQueries({
        queryKey: QUERY_KEYS.sourcesInfinite(variables.notebookId),
        refetchType: 'active'
      })
      toast({
        title: t('common.success'),
        description: t('sources.fileUploadedSuccess'),
      })
    },
    onError: (error: unknown) => {
      toast({
        title: t('common.error'),
        description: getApiErrorMessage(error, (key) => t(key), t('sources.failedToUploadFile')),
        variant: 'destructive',
      })
    },
  })
}

// RENDERING.md R1.3: how long to wait before retrying a dropped stream connection. A fixed,
// short delay rather than native EventSource's own (opaque, and -- measured against this
// port's own probe, `open-notebook-port/probes/r1_3_stream_cut.py` -- unreliable after a
// few consecutive connection failures: it stopped retrying altogether rather than
// continuing to back off) retry behavior.
const STATUS_STREAM_RETRY_MS = 2000

/**
 * RENDERING.md R1/R4: subscribes to `GET /api/sources/{id}/status/stream` (a server-sent
 * event stream backed by SourceEntity's own notification publisher -- see
 * open-notebook-akka's ApiSourceEndpoint) instead of polling `GET /api/sources/{id}/status`
 * every 2 seconds. The first event is the source's current status (R1.4: no extra round
 * trip needed to see it).
 *
 * Reads the stream with `fetch` + a manual `ReadableStream` line reader and its own
 * reconnect loop, the same shape the source's own `useAsk`/`useSourceChat` SSE consumers
 * already use for their two streaming routes (see `search.ts`/`source-chat.ts`), rather
 * than the native `EventSource` API. `open-notebook-port/probes/r1_3_stream_cut.py` cuts
 * the connection for real (RENDERING.md R1.3 warns the browser's offline switch does not)
 * and found `EventSource` recovers on the *first* disconnect but stops retrying after a
 * handful of consecutive TCP-level refusals during a held-open outage -- a real defect
 * this explicit loop does not have, since it always re-arms its own retry timer regardless
 * of how the previous attempt failed. Same external shape (`{ data, isLoading }`) as the
 * TanStack Query hook this replaces, so SourceCard.tsx -- the one caller -- needed no change.
 */
export function useSourceStatus(sourceId: string, enabled = true) {
  const [data, setData] = useState<SourceStatusResponse | undefined>(undefined)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    if (!sourceId || !enabled) {
      setIsLoading(false)
      return
    }
    setIsLoading(true)
    let stopped = false
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    let abortController: AbortController | null = null

    const connect = async () => {
      if (stopped) return
      abortController = new AbortController()
      try {
        const response = await fetch(`/api/sources/${sourceId}/status/stream`, {
          headers: { Accept: 'text/event-stream' },
          signal: abortController.signal,
        })
        if (!response.ok || !response.body) throw new Error(`status ${response.status}`)
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        while (!stopped) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() ?? ''
          for (const line of lines) {
            if (!line.startsWith('data:')) continue
            try {
              setData(JSON.parse(line.slice(5).trim()) as SourceStatusResponse)
              setIsLoading(false)
            } catch {
              // Incomplete/malformed frame -- wait for the next one rather than crashing.
            }
          }
        }
      } catch {
        // Falls through to the retry schedule below regardless of failure mode (network
        // error, non-2xx response, or a connection reset mid-stream).
      }
      if (!stopped) {
        retryTimer = setTimeout(connect, STATUS_STREAM_RETRY_MS)
      }
    }

    connect()

    return () => {
      stopped = true
      if (retryTimer) clearTimeout(retryTimer)
      abortController?.abort()
    }
  }, [sourceId, enabled])

  return { data, isLoading }
}

export function useRetrySource() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { t } = useTranslation()

  return useMutation({
    mutationFn: (sourceId: string) => sourcesApi.retry(sourceId),
    onSuccess: (result, sourceId) => {
      // Invalidate status query to refetch latest status
      queryClient.invalidateQueries({
        queryKey: ['sources', sourceId, 'status']
      })
      // Invalidate ALL sources queries to refresh the UI
      queryClient.invalidateQueries({ queryKey: ['sources'] })
      queryClient.invalidateQueries({ queryKey: QUERY_KEYS.source(sourceId) })

      toast({
        title: t('sources.sourceRequeued'),
        description: t('sources.sourceRequeuedDesc'),
      })
    },
    onError: (error: unknown) => {
      toast({
        title: t('common.error'),
        description: getApiErrorMessage(error, (key) => t(key), t('sources.failedToRetry')),
        variant: 'destructive',
      })
    },
  })
}

export function useAddSourcesToNotebook() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { t } = useTranslation()

  return useMutation({
    mutationFn: async ({ notebookId, sourceIds }: { notebookId: string; sourceIds: string[] }) => {
      const { notebooksApi } = await import('@/lib/api/notebooks')

      // Use Promise.allSettled to handle partial failures gracefully
      const results = await Promise.allSettled(
        sourceIds.map(sourceId => notebooksApi.addSource(notebookId, sourceId))
      )

      // Count successes and failures
      const successes = results.filter(r => r.status === 'fulfilled').length
      const failures = results.filter(r => r.status === 'rejected').length

      return { successes, failures, total: sourceIds.length }
    },
    onSuccess: (result, { notebookId, sourceIds }) => {
      // Invalidate ALL sources queries to refresh all lists
      queryClient.invalidateQueries({ queryKey: ['sources'] })
      // Specifically invalidate the notebook's sources
      queryClient.invalidateQueries({ queryKey: QUERY_KEYS.sources(notebookId) })
      // Invalidate each affected source
      sourceIds.forEach(sourceId => {
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.source(sourceId) })
      })

      // Show appropriate toast based on results
      if (result.failures === 0) {
        toast({
          title: t('common.success'),
          description: t('sources.sourcesAddedToNotebook', { count: result.successes }),
        })
      } else if (result.successes === 0) {
        toast({
          title: t('common.error'),
          description: t('sources.failedToAddSourcesToNotebook'),
          variant: 'destructive',
        })
      } else {
        toast({
          title: t('common.success'),
          description: t('sources.partialAddSuccess', { success: result.successes.toString(), failed: result.failures.toString() }),
          variant: 'default',
        })
      }
    },
    onError: (error: unknown) => {
      toast({
        title: t('common.error'),
        description: getApiErrorMessage(error, (key) => t(key), t('sources.failedToAddSourcesToNotebook')),
        variant: 'destructive',
      })
    },
  })
}

export function useRemoveSourceFromNotebook() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { t } = useTranslation()

  return useMutation({
    mutationFn: async ({ notebookId, sourceId }: { notebookId: string; sourceId: string }) => {
      // This will call the API we created
      const { notebooksApi } = await import('@/lib/api/notebooks')
      return notebooksApi.removeSource(notebookId, sourceId)
    },
    onSuccess: (_, { notebookId, sourceId }) => {
      // Invalidate ALL sources queries to refresh all lists
      queryClient.invalidateQueries({ queryKey: ['sources'] })
      // Specifically invalidate the notebook's sources
      queryClient.invalidateQueries({ queryKey: QUERY_KEYS.sources(notebookId) })
      // Also invalidate the specific source
      queryClient.invalidateQueries({ queryKey: QUERY_KEYS.source(sourceId) })

      toast({
        title: t('common.success'),
        description: t('sources.sourceRemovedFromNotebook'),
      })
    },
    onError: (error: unknown) => {
      toast({
        title: t('common.error'),
        description: getApiErrorMessage(error, (key) => t(key), t('sources.failedToRemoveSourceFromNotebook')),
        variant: 'destructive',
      })
    },
  })
}
