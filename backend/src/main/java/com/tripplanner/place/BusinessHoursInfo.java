package com.tripplanner.place;

import jakarta.validation.constraints.Size;

/** Raw public-source evidence is kept so changed visit times can be checked again. */
public record BusinessHoursInfo(@Size(max=40) String contentId,@Size(max=2000) String hoursText,
        @Size(max=1000) String closedText,@Size(max=40) String checkedAt,@Size(max=10) String visitDate,
        @Size(max=20) String status,@Size(max=300) String detail,
        @Size(max=10) String checkedVisitTime,@Size(max=20) String basis) {
    public BusinessHoursInfo(String contentId,String hoursText,String closedText,String checkedAt,String visitDate,String status,String detail) {
        this(contentId,hoursText,closedText,checkedAt,visitDate,status,detail,null,null);
    }
    public BusinessHoursInfo assessed(String date,String state,String reason) {
        return new BusinessHoursInfo(contentId,hoursText,closedText,checkedAt,date,state,reason,checkedVisitTime,basis);
    }
    public static BusinessHoursInfo unknown(String reason) {return new BusinessHoursInfo(null,null,null,null,null,"UNKNOWN",reason);}
}
