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

/** Modelo legacy (PostgreSQL) */
export interface SesionLegacy {
  sessionId: string;
  usuario: Usuario | null;
  timestampUtc: string | null;
  sessionLength: number | null;
  interactionCount: number | null;
  canal: Canal | null;
  fuenteTrafico: FuenteTrafico | null;
}

/** Modelo actual (ClickHouse - fact_eventos agrupado) */
export interface Sesion {
  sessionId: string;
  userId: string | null;
  timestampUtc: string | null;
  sessionLength: number | null;
  interactionCount: number | null;
  channel: string | null;
  isConversion: number | null;
  dropOffFlag: number | null;
  timeSpentSec: number | null;
  eventIndex: number | null;
  userAction: string | null;
  productId: string | null;
  price: number | null;
  semana: number | null;
}
