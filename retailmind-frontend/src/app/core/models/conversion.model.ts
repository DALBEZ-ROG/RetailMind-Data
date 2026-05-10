export interface Conversion {
  conversionId: number;
  sesion: { sessionId: string } | null;
  isConversion: boolean | null;
  dropOffFlag: boolean | null;
  conversionTime: string | null;
}

export interface ConversionResumen {
  conversiones: number;
  noConversiones: number;
  total: number;
}
