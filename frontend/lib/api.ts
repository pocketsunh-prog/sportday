const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api';

export interface AuthResponse {
  token: string;
  username: string;
  role: string;
  fullName: string;
  userId: number;
}

export interface UserDTO {
  id: number;
  username: string;
  email: string;
  fullName: string;
  age?: number;
  gender?: string;
  role: string;
  enabled: boolean;
  createdAt: string;
}

export interface EventDTO {
  id: number;
  name: string;
  description: string;
  type: string;
  eventDate: string;
  location: string;
  maxParticipants: number;
  enabled: boolean;
  createdAt: string;
  enrolledCount?: number;
}

export interface EventResultDTO {
  id: number;
  userId: number;
  username: string;
  fullName: string;
  eventId: number;
  eventName: string;
  mark: number;
  unit?: string;
  notes?: string;
  recordedAt: string;
}

export interface Enrollment {
  id: number;
  user: UserDTO;
  event: EventDTO;
  status: string;
  enrolledAt: string;
}

class ApiClient {
  private getToken(): string | null {
    if (typeof window !== 'undefined') {
      return localStorage.getItem('token');
    }
    return null;
  }

  private getHeaders(): HeadersInit {
    const headers: HeadersInit = { 'Content-Type': 'application/json' };
    const token = this.getToken();
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    return headers;
  }

  private async request<T>(url: string, options: RequestInit = {}): Promise<T> {
    const response = await fetch(`${API_BASE}${url}`, {
      ...options,
      headers: { ...this.getHeaders(), ...options.headers },
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed' }));
      if (error.errors) {
        const messages = Object.entries(error.errors).map(([field, msg]) => `${field}: ${msg}`);
        throw new Error(messages.join('\n'));
      }
      throw new Error(error.message || `HTTP ${response.status}`);
    }

    if (response.status === 204) return undefined as T;
    return response.json();
  }

  // Auth
  async login(username: string, password: string): Promise<AuthResponse> {
    return this.request('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
  }

  async register(data: {
    username: string;
    password: string;
    email: string;
    fullName?: string;
    age?: number;
    gender?: string;
  }): Promise<AuthResponse> {
    return this.request('/auth/register', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  // Events
  async getEvents(onlyEnabled = false): Promise<EventDTO[]> {
    return this.request(`/events?onlyEnabled=${onlyEnabled}`);
  }

  async getEvent(id: number): Promise<EventDTO> {
    return this.request(`/events/${id}`);
  }

  async createEvent(event: Partial<EventDTO>): Promise<EventDTO> {
    return this.request('/events', {
      method: 'POST',
      body: JSON.stringify(event),
    });
  }

  async updateEvent(id: number, event: Partial<EventDTO>): Promise<EventDTO> {
    return this.request(`/events/${id}`, {
      method: 'PUT',
      body: JSON.stringify(event),
    });
  }

  async setEventEnabled(id: number, enabled: boolean): Promise<EventDTO> {
    return this.request(`/events/${id}/enable?enabled=${enabled}`, {
      method: 'PATCH',
    });
  }

  async deleteEvent(id: number): Promise<void> {
    return this.request(`/events/${id}`, { method: 'DELETE' });
  }

  // Enrollments
  async enroll(eventId: number): Promise<Enrollment> {
    return this.request(`/enrollments/${eventId}`, { method: 'POST' });
  }

  async cancelEnrollment(eventId: number): Promise<void> {
    return this.request(`/enrollments/${eventId}`, { method: 'DELETE' });
  }

  async getMyEnrollments(): Promise<Enrollment[]> {
    return this.request('/enrollments/my');
  }

  async checkEnrollment(eventId: number): Promise<boolean> {
    return this.request(`/enrollments/check/${eventId}`);
  }

  // Results
  async getEventResults(eventId: number): Promise<EventResultDTO[]> {
    return this.request(`/results/event/${eventId}`);
  }

  async getUserResults(userId: number): Promise<EventResultDTO[]> {
    return this.request(`/results/user/${userId}`);
  }

  async recordResult(
    userId: number,
    eventId: number,
    mark: number,
    unit?: string,
    notes?: string
  ): Promise<EventResultDTO> {
    const params = new URLSearchParams({
      userId: String(userId),
      eventId: String(eventId),
      mark: String(mark),
    });
    if (unit) params.set('unit', unit);
    if (notes) params.set('notes', notes);
    return this.request(`/results?${params.toString()}`, { method: 'POST' });
  }

  async deleteResult(id: number): Promise<void> {
    return this.request(`/results/${id}`, { method: 'DELETE' });
  }

  // Users
  async getCurrentUser(): Promise<UserDTO> {
    return this.request('/users/me');
  }

  async updateCurrentUser(data: Partial<UserDTO>): Promise<UserDTO> {
    return this.request('/users/me', {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }

  async getAllUsers(): Promise<UserDTO[]> {
    return this.request('/users');
  }

  async setUserEnabled(id: number, enabled: boolean): Promise<void> {
    return this.request(`/users/${id}/enable?enabled=${enabled}`, { method: 'PATCH' });
  }

  async deleteUser(id: number): Promise<void> {
    return this.request(`/users/${id}`, { method: 'DELETE' });
  }
}

export const api = new ApiClient();
