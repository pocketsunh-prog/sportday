package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A relay team's new name.
 *
 * <p>The name is what the school writes on the sheet — {@code 1A}, {@code C Grade
 * Yellow} — so it is free text. It is not the team's key: renaming a team changes
 * what is printed, never which team it is.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RelayRenameRequest {

    private String name;
}
