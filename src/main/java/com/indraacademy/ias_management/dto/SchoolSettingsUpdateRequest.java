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
    // Academic calendar settings — configurable per school
    private Integer academicYearStartMonth;
    private String workingDays;
    private String gradingSystem;
    private String affiliationNumber;
    private String schoolCode;

    // Staff attendance / GPS check-in settings
    private Double schoolLatitude;
    private Double schoolLongitude;
    private Integer geofenceRadius;
    private String schoolStartTime;       // "HH:mm" format
    private Integer lateThresholdMinutes;
    private String checkinWindowStart;    // "HH:mm" format
    private String checkinWindowEnd;      // "HH:mm" format

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

    public Integer getAcademicYearStartMonth() { return academicYearStartMonth; }
    public void setAcademicYearStartMonth(Integer academicYearStartMonth) { this.academicYearStartMonth = academicYearStartMonth; }

    public String getWorkingDays() { return workingDays; }
    public void setWorkingDays(String workingDays) { this.workingDays = workingDays; }

    public String getGradingSystem() { return gradingSystem; }
    public void setGradingSystem(String gradingSystem) { this.gradingSystem = gradingSystem; }

    public String getAffiliationNumber() { return affiliationNumber; }
    public void setAffiliationNumber(String affiliationNumber) { this.affiliationNumber = affiliationNumber; }

    public String getSchoolCode() { return schoolCode; }
    public void setSchoolCode(String schoolCode) { this.schoolCode = schoolCode; }

    public Double getSchoolLatitude() { return schoolLatitude; }
    public void setSchoolLatitude(Double schoolLatitude) { this.schoolLatitude = schoolLatitude; }

    public Double getSchoolLongitude() { return schoolLongitude; }
    public void setSchoolLongitude(Double schoolLongitude) { this.schoolLongitude = schoolLongitude; }

    public Integer getGeofenceRadius() { return geofenceRadius; }
    public void setGeofenceRadius(Integer geofenceRadius) { this.geofenceRadius = geofenceRadius; }

    public String getSchoolStartTime() { return schoolStartTime; }
    public void setSchoolStartTime(String schoolStartTime) { this.schoolStartTime = schoolStartTime; }

    public Integer getLateThresholdMinutes() { return lateThresholdMinutes; }
    public void setLateThresholdMinutes(Integer lateThresholdMinutes) { this.lateThresholdMinutes = lateThresholdMinutes; }

    public String getCheckinWindowStart() { return checkinWindowStart; }
    public void setCheckinWindowStart(String checkinWindowStart) { this.checkinWindowStart = checkinWindowStart; }

    public String getCheckinWindowEnd() { return checkinWindowEnd; }
    public void setCheckinWindowEnd(String checkinWindowEnd) { this.checkinWindowEnd = checkinWindowEnd; }
}
