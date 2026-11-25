package com.tripplanner.trip;
import com.tripplanner.user.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
@RestController @RequestMapping("/api/trips/{id}")
public class TripEditController {
 private final TripEditLocks locks; private final TripService trips;
 public TripEditController(TripEditLocks locks,TripService trips){this.locks=locks;this.trips=trips;}
 @GetMapping("/edit-lock") public DraftEditLocks.Status status(@PathVariable Long id,@AuthenticationPrincipal Jwt jwt,@RequestHeader(value=DraftEditLocks.HEADER,required=false) String token){return locks.status(id,CurrentUser.id(jwt),token);}
 @PostMapping("/edit-lock") public DraftEditLocks.Grant acquire(@PathVariable Long id,@AuthenticationPrincipal Jwt jwt,@RequestHeader(value=DraftEditLocks.HEADER,required=false) String token){return locks.acquire(id,CurrentUser.id(jwt),token);}
 @PutMapping("/edit-lock") public DraftEditLocks.Status renew(@PathVariable Long id,@AuthenticationPrincipal Jwt jwt,@RequestHeader(value=DraftEditLocks.HEADER,required=false) String token){return locks.renew(id,CurrentUser.id(jwt),token);}
 @DeleteMapping("/edit-lock") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void release(@PathVariable Long id,@AuthenticationPrincipal Jwt jwt,@RequestHeader(value=DraftEditLocks.HEADER,required=false) String token){locks.release(id,CurrentUser.id(jwt),token);}
 @PutMapping("/plan") public TripDtos.TripResponse save(@PathVariable Long id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody TripService.PlanUpdate request){return trips.savePlan(id,request,CurrentUser.id(jwt));}
}
