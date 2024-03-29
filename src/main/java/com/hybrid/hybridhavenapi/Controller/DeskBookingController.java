package com.hybrid.hybridhavenapi.Controller;

import com.hybrid.hybridhavenapi.Entity.DeskBooking;
import com.hybrid.hybridhavenapi.Service.DeskBookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/desk-bookings")
public class DeskBookingController {
    @Autowired
    private DeskBookingService deskBookingService;

    @PostMapping
    public ResponseEntity<DeskBooking> createDeskBooking(@RequestBody DeskBooking deskBooking) {
        DeskBooking savedDeskBooking = deskBookingService.saveDeskBooking(deskBooking);
        return new ResponseEntity<>(savedDeskBooking, HttpStatus.CREATED);
    }

    @GetMapping("/id/{id}")
    public ResponseEntity<DeskBooking> getDeskBookingById(@PathVariable Integer id) {
        DeskBooking deskBooking = deskBookingService.getDeskBookingById(id);
        if (deskBooking != null) {
            return new ResponseEntity<>(deskBooking, HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping
    public ResponseEntity<List<DeskBooking>> getAllDeskBookings() {
        List<DeskBooking> deskBookingList = deskBookingService.getAllDeskBookings();
        return new ResponseEntity<>(deskBookingList, HttpStatus.OK);
    }

    @DeleteMapping("/id/{id}")
    public ResponseEntity<Void> deleteDeskBooking(@PathVariable Integer id) {
        deskBookingService.deleteDeskBooking(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
