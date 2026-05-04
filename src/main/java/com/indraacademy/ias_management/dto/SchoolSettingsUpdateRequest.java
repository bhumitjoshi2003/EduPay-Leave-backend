package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.BoardType;

/**
 * Fields an ADMIN is allowed to update on their school record.
 * Subscription plan, maxStudents, and expiryDate are managed by SUPER_ADMIN only.
 */
public class SchoolSettingsUpdateRequest {

    private String name;
    private BoardType boardType;
    private String address;
    private String city;
    private String state;
    private String pincode;
    private String email;
    private String phone;
    private String website;
    private String logoUrl;
    private String themeColor;
    private String contactPersonName;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BoardType getBoardType() { return boardType; }
    public void setBoardType(BoardType boardType) { this.boardType = boardType; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public String getThemeColor() { return themeColor; }
    public void setThemeColor(String themeColor) { this.themeColor = themeColor; }

    public String getContactPersonName() { return contactPersonName; }
    public void setContactPersonName(String contactPersonName) { this.contactPersonName = contactPersonName; }
}
