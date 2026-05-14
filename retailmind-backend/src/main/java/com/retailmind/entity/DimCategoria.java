package com.retailmind.entity;

/**
 * Representa un registro de la tabla dim_categoria en ClickHouse.
 */
public class DimCategoria {

    private Long   categoriaId;
    private String categoriaNombre;

    public DimCategoria() {}

    public DimCategoria(Long categoriaId, String categoriaNombre) {
        this.categoriaId = categoriaId;
        this.categoriaNombre = categoriaNombre;
    }

    public Long getCategoriaId()              { return categoriaId; }
    public void setCategoriaId(Long v)        { this.categoriaId = v; }

    public String getCategoriaNombre()        { return categoriaNombre; }
    public void setCategoriaNombre(String v)  { this.categoriaNombre = v; }
}
