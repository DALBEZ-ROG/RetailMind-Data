export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token:        string;
  refreshToken: string;
  username:     string;
  nombre:       string;
  rol:          string;
  expiresIn:    number;
}

export interface AuthUser {
  username: string;
  nombre:   string;
  rol:      string;
}
