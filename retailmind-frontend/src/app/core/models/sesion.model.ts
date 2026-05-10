import { Usuario } from './usuario.model';

export interface Canal {
  channelId: number;
  channelName: string;
}

export interface FuenteTrafico {
  sourceId: number;
  sourceName: string;
  type: string | null;
}

export interface Sesion {
  sessionId: string;
  usuario: Usuario | null;
  timestampUtc: string | null;
  sessionLength: number | null;
  interactionCount: number | null;
  canal: Canal | null;
  fuenteTrafico: FuenteTrafico | null;
}
