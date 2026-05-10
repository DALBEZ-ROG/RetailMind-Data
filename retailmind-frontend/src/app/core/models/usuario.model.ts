export interface Region {
  regionId: number;
  regionName: string;
  country: string | null;
}

export interface Dispositivo {
  deviceTypeId: number;
  deviceTypeName: string;
}

export interface Usuario {
  userId: string;
  region: Region | null;
  dispositivo: Dispositivo | null;
  createdAt: string | null;
}
