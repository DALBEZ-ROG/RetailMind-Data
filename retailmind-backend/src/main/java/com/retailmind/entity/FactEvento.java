package com.retailmind.entity;

/**
 * Representa un registro de la tabla fact_eventos en ClickHouse.
 * No es una entidad JPA (ClickHouse no soporta Hibernate).
 * Se usa con JdbcTemplate + RowMapper.
 */
public class FactEvento {

    private Long    eventPk;
    private String  sessionId;
    private String  userId;
    private String  timestampUtc;
    private Integer eventIndex;
    private String  userAction;
    private String  productId;
    private Float   timeSpentSec;
    private Float   sessionLength;
    private Integer interactionCount;
    private Integer isConversion;
    private Integer dropOffFlag;
    private Float   price;
    private String  channel;
    private Integer semana;

    public FactEvento() {}

    public Long getEventPk()                    { return eventPk; }
    public void setEventPk(Long v)              { this.eventPk = v; }

    public String getSessionId()                { return sessionId; }
    public void setSessionId(String v)          { this.sessionId = v; }

    public String getUserId()                   { return userId; }
    public void setUserId(String v)             { this.userId = v; }

    public String getTimestampUtc()             { return timestampUtc; }
    public void setTimestampUtc(String v)       { this.timestampUtc = v; }

    public Integer getEventIndex()              { return eventIndex; }
    public void setEventIndex(Integer v)        { this.eventIndex = v; }

    public String getUserAction()               { return userAction; }
    public void setUserAction(String v)         { this.userAction = v; }

    public String getProductId()                { return productId; }
    public void setProductId(String v)          { this.productId = v; }

    public Float getTimeSpentSec()              { return timeSpentSec; }
    public void setTimeSpentSec(Float v)        { this.timeSpentSec = v; }

    public Float getSessionLength()             { return sessionLength; }
    public void setSessionLength(Float v)       { this.sessionLength = v; }

    public Integer getInteractionCount()        { return interactionCount; }
    public void setInteractionCount(Integer v)  { this.interactionCount = v; }

    public Integer getIsConversion()            { return isConversion; }
    public void setIsConversion(Integer v)      { this.isConversion = v; }

    public Integer getDropOffFlag()             { return dropOffFlag; }
    public void setDropOffFlag(Integer v)       { this.dropOffFlag = v; }

    public Float getPrice()                     { return price; }
    public void setPrice(Float v)               { this.price = v; }

    public String getChannel()                  { return channel; }
    public void setChannel(String v)            { this.channel = v; }

    public Integer getSemana()                  { return semana; }
    public void setSemana(Integer v)            { this.semana = v; }
}
