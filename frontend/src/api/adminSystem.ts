import { adminFetch } from './adminClient.ts';

export interface ComponentHealth {
  status: string;
  details?: Record<string, any>;
}

export interface BreakerStatus {
  name: string;
  state: 'CLOSED' | 'HALF_OPEN' | 'OPEN' | string;
  failureRate: number;
}

export interface AdminSystemStatusResponse {
  status: 'UP' | 'DOWN' | 'DEGRADED' | string;
  timestamp: string;
  components: Record<string, ComponentHealth>;
  circuitBreakers: BreakerStatus[];
  grafanaLinks: Record<string, string>;
}

export async function getAdminSystemStatus(): Promise<AdminSystemStatusResponse> {
  const response = await adminFetch('/api/v1/admin/system/status');
  if (!response.ok) {
    throw new Error(`Failed to fetch system status (HTTP ${response.status})`);
  }
  return response.json();
}
