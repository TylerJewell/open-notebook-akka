import type { AxiosResponse } from 'axios'

import apiClient from './client'
import { 
  SourceListResponse, 
  SourceDetailResponse, 
  SourceResponse,
  SourceStatusResponse,
  CreateSourceRequest, 
  UpdateSourceRequest 
} from '@/lib/types/api'

export type SourceSortField = 'type' | 'title' | 'created' | 'updated' | 'insights_count' | 'embedded'

export const sourcesApi = {
  list: async (params?: {
    notebook_id?: string
    limit?: number
    offset?: number
    sort_by?: SourceSortField
    sort_order?: 'asc' | 'desc'
  }) => {
    const response = await apiClient.get<SourceListResponse[]>('/sources', { params })
    return response.data
  },

  get: async (id: string) => {
    const response = await apiClient.get<SourceDetailResponse>(`/sources/${id}`)
    return response.data
  },

  create: async (data: CreateSourceRequest & { file?: File }) => {
    // Akka port note (RENDERING.md R4): the source always posts multipart/form-data here,
    // including for plain text/link submission, because its file-upload and non-file paths
    // share one endpoint. This backend's HTTP endpoints bind a JSON body via Jackson and have
    // no multipart-parsing hook (see open-notebook-akka's ApiSourceEndpoint class doc), and
    // SPEC-001 already excludes the original's raw file-upload HTTP surface, so a JSON body is
    // sent instead for the text/link case this backend actually supports. A caller-supplied
    // `file` has nothing to bind to on the server; surfaced client-side rather than silently
    // dropped server-side.
    if (data.file instanceof File) {
      throw new Error('File upload is not supported by this backend (see SPEC-001 SS1).')
    }
    const response = await apiClient.post<SourceResponse>('/sources', {
      type: data.type,
      notebooks: data.notebooks,
      notebook_id: data.notebook_id,
      title: data.title,
      url: data.url,
      content: data.content,
    })
    return response.data
  },

  update: async (id: string, data: UpdateSourceRequest) => {
    const response = await apiClient.put<SourceListResponse>(`/sources/${id}`, data)
    return response.data
  },

  delete: async (id: string) => {
    await apiClient.delete(`/sources/${id}`)
  },

  status: async (id: string) => {
    const response = await apiClient.get<SourceStatusResponse>(`/sources/${id}/status`)
    return response.data
  },

  upload: async (file: File, notebook_id: string) => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('notebook_id', notebook_id)
    formData.append('type', 'upload')
    formData.append('async_processing', 'true')
    
    const response = await apiClient.post<SourceResponse>('/sources', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    })
    return response.data
  },

  retry: async (id: string) => {
    const response = await apiClient.post<SourceResponse>(`/sources/${id}/retry`)
    return response.data
  },

  downloadFile: async (id: string): Promise<AxiosResponse<Blob>> => {
    return apiClient.get(`/sources/${id}/download`, {
      responseType: 'blob',
    })
  },
}
