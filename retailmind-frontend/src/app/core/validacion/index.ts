import { CampoNumeroDirective } from './campo-numero.directive';
import { CampoTextoDirective } from './campo-texto.directive';

export * from './campo-base.directive';
export * from './campo-numero.directive';
export * from './campo-texto.directive';
export * from './perfiles-texto';

/**
 * Lo que se añade a `imports` de un componente standalone para tener las dos
 * directivas. Va como constante y no como módulo porque toda la aplicación es
 * standalone: un `NgModule` solo para esto sería la única excepción del proyecto.
 */
export const VALIDACION_CAMPOS = [CampoNumeroDirective, CampoTextoDirective] as const;
