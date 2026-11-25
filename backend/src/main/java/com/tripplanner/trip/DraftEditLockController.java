package com.tripplanner.trip;

import com.tripplanner.user.CurrentUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController @RequestMapping("/api/drafts/{id}/edit-lock")
public class DraftEditLockController {
    private final DraftEditLocks locks;
    public DraftEditLockController(DraftEditLocks locks){this.locks=locks;}
    @GetMapping public DraftEditLocks.Status status(@PathVariable String id,@AuthenticationPrincipal Jwt jwt,@RequestHeader(value=DraftEditLocks.HEADER,required=false) String token){return locks.status(id,CurrentUser.id(jwt),token);}
    @PostMapping public DraftEditLocks.Grant acquire(@PathVariable String id,@AuthenticationPrincipal Jwt jwt,@RequestHeader(value=DraftEditLocks.HEADER,required=false) String token){return locks.acquire(id,CurrentUser.id(jwt),token);}
    @PutMapping public DraftEditLocks.Status renew(@PathVariable String id,@AuthenticationPrincipal Jwt jwt,@RequestHeader(value=DraftEditLocks.HEADER,required=false) String token){return locks.renew(id,CurrentUser.id(jwt),token);}
    @DeleteMapping @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void release(@PathVariable String id,@AuthenticationPrincipal Jwt jwt,@RequestHeader(value=DraftEditLocks.HEADER,required=false) String token){locks.release(id,CurrentUser.id(jwt),token);}
}
