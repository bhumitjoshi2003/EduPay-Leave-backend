package com.indraacademy.ias_management.dto;

public class PublicSchoolResponse {

    private String name;
    private String slug;
    private String logoUrl;
    private String themeColor;
    private String city;
    private String boardType;

    public PublicSchoolResponse() {}

    public PublicSchoolResponse(String name, String slug, String logoUrl,
                                String themeColor, String city, String boardType) {
        this.name = name;
        this.slug = slug;
        this.logoUrl = logoUrl;
        this.themeColor = themeColor;
        this.city = city;
        this.boardType = boardType;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public String getThemeColor() { return themeColor; }
    public void setThemeColor(String themeColor) { this.themeColor = themeColor; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getBoardType() { return boardType; }
    public void setBoardType(String boardType) { this.boardType = boardType; }
}
