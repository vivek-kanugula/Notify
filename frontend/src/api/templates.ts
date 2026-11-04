import { apiFetch } from './client';

export type TemplateChannel = 'EMAIL' | 'WEBHOOK' | 'IN_APP';
export type TemplateContentType = 'TEXT' | 'HTML';
export type TemplateVersionStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export interface TemplateVersion {
  id: string;
  templateId: string;
  versionNumber: number;
  status: TemplateVersionStatus;
  subject: string | null;
  body: string;
  contentType: TemplateContentType;
  variables: string[];
  createdAt: string;
  updatedAt: string;
}

export interface Template {
  id: string;
  name: string;
  channel: TemplateChannel;
  description: string | null;
  deletedAt: string | null;
  createdAt: string;
  updatedAt: string;
  currentVersion?: TemplateVersion | null;
}

export interface TemplateDetail extends Template {
  versions: TemplateVersion[];
}

export interface CreateTemplateRequest {
  name: string;
  channel: TemplateChannel;
  description?: string;
  subject?: string;
  body: string;
  contentType?: TemplateContentType;
}

export interface UpdateTemplateRequest {
  name?: string;
  description?: string;
}

export interface CreateVersionRequest {
  cloneFromVersion?: number;
  subject?: string;
  body?: string;
  contentType?: TemplateContentType;
}

export interface UpdateVersionRequest {
  subject?: string;
  body?: string;
  contentType?: TemplateContentType;
}

export interface PreviewTemplateRequest {
  variables: Record<string, unknown>;
}

export interface RenderedTemplateResponse {
  subject: string | null;
  body: string;
  contentType: TemplateContentType;
}

export async function listTemplates(): Promise<Template[]> {
  const res = await apiFetch('/api/v1/templates');
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch templates' }));
    throw new Error(error.detail || 'Failed to fetch templates');
  }
  return res.json();
}

export async function getTemplate(id: string): Promise<TemplateDetail> {
  const res = await apiFetch(`/api/v1/templates/${id}`);
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch template' }));
    throw new Error(error.detail || 'Failed to fetch template');
  }
  return res.json();
}

export async function createTemplate(request: CreateTemplateRequest): Promise<Template> {
  const res = await apiFetch('/api/v1/templates', {
    method: 'POST',
    body: JSON.stringify(request),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to create template' }));
    throw new Error(error.detail || 'Failed to create template');
  }
  return res.json();
}

export async function updateTemplate(id: string, request: UpdateTemplateRequest): Promise<Template> {
  const res = await apiFetch(`/api/v1/templates/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to update template' }));
    throw new Error(error.detail || 'Failed to update template');
  }
  return res.json();
}

export async function deleteTemplate(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/templates/${id}`, {
    method: 'DELETE',
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to delete template' }));
    throw new Error(error.detail || 'Failed to delete template');
  }
}

export async function listVersions(templateId: string): Promise<TemplateVersion[]> {
  const res = await apiFetch(`/api/v1/templates/${templateId}/versions`);
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to list template versions' }));
    throw new Error(error.detail || 'Failed to list template versions');
  }
  return res.json();
}

export async function createDraftVersion(
  templateId: string,
  request: CreateVersionRequest
): Promise<TemplateVersion> {
  const res = await apiFetch(`/api/v1/templates/${templateId}/versions`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to create draft version' }));
    throw new Error(error.detail || 'Failed to create draft version');
  }
  return res.json();
}

export async function getVersion(templateId: string, versionNumber: number): Promise<TemplateVersion> {
  const res = await apiFetch(`/api/v1/templates/${templateId}/versions/${versionNumber}`);
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch template version' }));
    throw new Error(error.detail || 'Failed to fetch template version');
  }
  return res.json();
}

export async function updateDraftVersion(
  templateId: string,
  versionNumber: number,
  request: UpdateVersionRequest
): Promise<TemplateVersion> {
  const res = await apiFetch(`/api/v1/templates/${templateId}/versions/${versionNumber}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to update template version' }));
    throw new Error(error.detail || 'Failed to update template version');
  }
  return res.json();
}

export async function publishVersion(templateId: string, versionNumber: number): Promise<TemplateVersion> {
  const res = await apiFetch(`/api/v1/templates/${templateId}/versions/${versionNumber}/publish`, {
    method: 'POST',
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to publish template version' }));
    throw new Error(error.detail || 'Failed to publish template version');
  }
  return res.json();
}

export async function previewVersion(
  templateId: string,
  versionNumber: number,
  variables: Record<string, unknown>
): Promise<RenderedTemplateResponse> {
  const res = await apiFetch(`/api/v1/templates/${templateId}/versions/${versionNumber}/preview`, {
    method: 'POST',
    body: JSON.stringify({ variables }),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to preview template version' }));
    throw new Error(error.detail || 'Failed to preview template version');
  }
  return res.json();
}

export async function getVersionDirect(versionId: string): Promise<TemplateVersion> {
  const res = await apiFetch(`/api/v1/templates/versions/${versionId}`);
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch template version by direct id' }));
    throw new Error(error.detail || 'Failed to fetch template version by direct id');
  }
  return res.json();
}

export interface DeleteVersionsResponse {
  templateId: string;
  deletedVersions: number[];
  count: number;
}

export async function deleteTemplateVersion(templateId: string, versionNumber: number): Promise<void> {
  const res = await apiFetch(`/api/v1/templates/${templateId}/versions/${versionNumber}`, {
    method: 'DELETE',
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: `Failed to delete template version v${versionNumber}` }));
    throw new Error(error.detail || error.message || `Failed to delete template version v${versionNumber}`);
  }
}

export async function deleteTemplateVersions(templateId: string, versionNumbers: number[]): Promise<DeleteVersionsResponse> {
  const res = await apiFetch(`/api/v1/templates/${templateId}/versions/delete`, {
    method: 'POST',
    body: JSON.stringify({ versionNumbers }),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to delete template versions' }));
    throw new Error(error.detail || error.message || 'Failed to delete template versions');
  }
  return res.json();
}
