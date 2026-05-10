export interface Evento {
  eventoId: number;
  sesion: { sessionId: string } | null;
  eventIndex: number | null;
  userAction: string | null;
  timeSpentSec: number | null;
  producto: { productId: string; brand: string | null } | null;
}
