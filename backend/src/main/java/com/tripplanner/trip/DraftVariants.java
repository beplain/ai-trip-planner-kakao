package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import java.util.*;

/** All variant schedules live inside the existing draft document JSON. */
final class DraftVariants {
    private DraftVariants() {}
    static TripDraft withDays(TripDraft d,List<DayDraft> days,List<String> warnings,List<StoredDraftOption> options,String selected) {
        return new TripDraft(d.title(),d.destination(),d.startDate(),d.endDate(),d.budget(),d.styles(),days,d.source(),warnings,d.request(),d.travelDetails(),options,selected,d.interpretedRequest());
    }
    static void validate(TripDraft draft) {
        TravelSchedule.validateDraft(draft);
        if (draft.options() == null || draft.options().isEmpty()) {
            if (draft.selectedOption()!=null) throw ApiException.badRequest("초안 유형을 찾지 못했습니다.");
            return;
        }
        if (draft.options().size()>3) throw ApiException.badRequest("초안 유형은 최대 3개입니다.");
        Set<String> ids = new HashSet<>(); boolean found = false;
        for (var option : draft.options()) {
            if (!ids.add(option.id())) throw ApiException.badRequest("초안 유형이 중복됩니다.");
            if (option.days().size()!=draft.days().size()) throw ApiException.badRequest("유형별 여행 기간이 다릅니다.");
            for (int i=0;i<draft.days().size();i++) if (option.days().get(i).dayNumber()!=draft.days().get(i).dayNumber()
                    || !Objects.equals(option.days().get(i).date(),draft.days().get(i).date())) throw ApiException.badRequest("유형별 여행 날짜가 다릅니다.");
            TravelSchedule.validateDraft(withDays(draft,option.days(),option.warnings(),null,null));
            if (option.id().equals(draft.selectedOption())) {
                found = true;
                if (!Objects.equals(option.days(),draft.days())) throw ApiException.badRequest("선택한 유형의 일정과 표시 일정이 다릅니다.");
            }
        }
        if (!found) throw ApiException.badRequest("선택한 초안 유형을 찾지 못했습니다.");
    }
    static TripDraft updateCurrent(TripDraft before,List<DayDraft> days,List<String> warnings) {
        if (before.options()==null || before.options().isEmpty()) return withDays(before,days,warnings,null,null);
        var options = before.options().stream().map(o -> o.id().equals(before.selectedOption())
                ? new StoredDraftOption(o.id(),o.label(),o.description(),days,warnings) : o).toList();
        return withDays(before,days,warnings,options,before.selectedOption());
    }
    static TripDraft switchTo(TripDraft draft,String id) {
        if (draft.options()==null) throw ApiException.badRequest("이 초안에는 다른 유형이 없습니다. 여행 정보에서 다시 생성해 주세요.");
        var current = updateCurrent(draft,draft.days(),draft.warnings());
        var selected = current.options().stream().filter(o -> o.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("초안 유형을 찾지 못했습니다."));
        var result = withDays(current,selected.days(),selected.warnings(),current.options(),selected.id());
        validate(result); return result;
    }
}
