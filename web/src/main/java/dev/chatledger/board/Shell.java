package dev.chatledger.board;

import dev.chatledger.security.Permission;
import dev.chatledger.security.Role;
import dev.chatledger.security.TenantUserDetails;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What every page needs: the tenant (name, currency, scale, zone), what this user may do, and the counts of the five columns. The
 * tenant and the user come from the signed-in principal only.
 */
@Component
public class Shell {

    private final BoardRepository board;

    public Shell(BoardRepository board) {
        this.board = board;
    }

    /** Fills the model with the tenant and the user's abilities. A tenant that cannot be read ends the request with 404. */
    public BoardRepository.TenantInfo fill(Model model, TenantUserDetails user) {
        BoardRepository.TenantInfo tenant = board.tenant(user.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Role role = user.role();
        model.addAttribute("tenant", tenant);
        model.addAttribute("role", role.name());
        model.addAttribute("seller", role == Role.SELLER);
        model.addAttribute("canDecide", role.allows(Permission.CONFIRM_OR_OVERRIDE_MATCH));
        model.addAttribute("canReverse", role.allows(Permission.REVERSE_TRANSACTION));
        model.addAttribute("canApprove", role.allows(Permission.APPROVE_ORDER_TOTAL));
        model.addAttribute("canExport", role.allows(Permission.EXPORT_DATA));
        model.addAttribute("canLedger", role.allows(Permission.VIEW_LEDGER));
        model.addAttribute("canRecordOrder", role.allows(Permission.RECORD_ORDER));
        model.addAttribute("canRecordPayment", role.allows(Permission.RECORD_PAYMENT));
        return tenant;
    }

    /** The count of each column, keyed by the column's path name ({@code awaiting}, {@code review}, ...), for the chips. */
    public void counts(Model model, UUID tenantId) {
        Map<String, Long> byName = new LinkedHashMap<>();
        board.counts(tenantId).forEach((column, count) -> byName.put(column.path(), count));
        model.addAttribute("counts", byName);
    }
}
