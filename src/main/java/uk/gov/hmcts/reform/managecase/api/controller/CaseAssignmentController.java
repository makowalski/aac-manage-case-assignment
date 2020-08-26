package uk.gov.hmcts.reform.managecase.api.controller;

import com.microsoft.applicationinsights.boot.dependencies.apachecommons.lang3.StringUtils;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Example;
import io.swagger.annotations.ExampleProperty;
import org.modelmapper.ModelMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.managecase.api.errorhandling.ApiError;
import uk.gov.hmcts.reform.managecase.api.errorhandling.ValidationError;
import uk.gov.hmcts.reform.managecase.api.payload.CaseAssignmentRequest;
import uk.gov.hmcts.reform.managecase.api.payload.CaseAssignmentResponse;
import uk.gov.hmcts.reform.managecase.api.payload.CaseUnassignmentRequest;
import uk.gov.hmcts.reform.managecase.api.payload.CaseUnassignmentResponse;
import uk.gov.hmcts.reform.managecase.api.payload.GetCaseAssignmentsResponse;
import uk.gov.hmcts.reform.managecase.domain.CaseAssignedUsers;
import uk.gov.hmcts.reform.managecase.domain.CaseAssignment;
import uk.gov.hmcts.reform.managecase.service.CaseAssignmentService;

import javax.validation.Valid;
import javax.validation.ValidationException;
import javax.validation.constraints.NotEmpty;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@Validated
@ConditionalOnProperty(value = "mca.conditional-apis.case-assignments.enabled", havingValue = "true")
public class CaseAssignmentController {

    public static final String ASSIGN_ACCESS_MESSAGE =
            "Roles %s from the organisation policies successfully assigned to the assignee.";
    public static final String UNASSIGN_ACCESS_MESSAGE = "Unassignment(s) performed successfully.";
    public static final String GET_ASSIGNMENTS_MESSAGE = "Case-User-Role assignments returned successfully";

    private final CaseAssignmentService caseAssignmentService;
    private final ModelMapper mapper;

    public CaseAssignmentController(CaseAssignmentService caseAssignmentService, ModelMapper mapper) {
        this.caseAssignmentService = caseAssignmentService;
        this.mapper = mapper;
    }

    @PostMapping(path = "/case-assignments", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Assign Access within Organisation", notes = "Assign Access within Organisation")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponses({
        @ApiResponse(
            code = 201,
            message = "Role from the organisation policy successfully assigned to the assignee."
        ),
        @ApiResponse(
            code = 400,
            message = "One of the following reasons.\n"
                + "1) Case ID has to be a valid 16-digit Luhn number \n"
                + "2) Case ID has to be 16-digits long \n"
                + "3) Case ID can not be empty \n"
                + "4) Case type ID can not be empty \n"
                + "5) Assignee IDAM ID can not be empty \n"
                + "6) Intended assignee has to be in the same organisation as that of the invoker. \n"
                + "7) Case ID has to be one for which a case role is represented by the invoker's organisation. \n"
                + "8) Intended assignee has to be a solicitor enabled in the jurisdiction of the case. \n",
            response = ApiError.class,
            examples = @Example({
                    @ExampleProperty(
                            value = "{\"message\": \"Intended assignee has to be in the same organisation of invoker\","
                                    + " \"status\": \"BAD_REQUEST\" }",
                            mediaType = APPLICATION_JSON_VALUE)
            })
        ),
        @ApiResponse(
            code = 401,
            message = "Authentication failure due to invalid / expired tokens (IDAM / S2S)."
        ),
        @ApiResponse(
            code = 403,
            message = "One of the following reasons.\n"
                + "1) UnAuthorised S2S service \n"
                + "2) The user is neither a case access administrator nor a solicitor with access to the jurisdiction"
        ),
        @ApiResponse(
            code = 500,
            message = "One of the following reasons.\n"
                + "1) Case could not be fetched"
        )
    })
    public CaseAssignmentResponse assignAccessWithinOrganisation(
            @Valid @RequestBody CaseAssignmentRequest requestPayload) {
        CaseAssignment caseAssignment = mapper.map(requestPayload, CaseAssignment.class);
        List<String> roles = caseAssignmentService.assignCaseAccess(caseAssignment);
        return new CaseAssignmentResponse(String.format(ASSIGN_ACCESS_MESSAGE, StringUtils.join(roles, ',')));
    }

    @GetMapping(path = "/case-assignments", produces = APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Get Case Assignments in My Organisation", notes = "Get Assignments in My Organisation")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(
                    code = 200,
                    message = "Case-User-Role assignments returned successfully.",
                    response = GetCaseAssignmentsResponse.class,
                    examples = @Example({
                            @ExampleProperty(
                                    value = "{\n"
                                            + "  \"status_message\": \"Case-User-Role assignments returned "
                                            + "successfully\",\n"
                                            + "  \"case_assignments\": [\n"
                                            + "    {\n"
                                            + "      \"case_id\": \"1588234985453946\",\n"
                                            + "      \"shared_with\": [\n"
                                            + "        {\n"
                                            + "          \"idam_id\": \"221a2877-e1ab-4dc4-a9ff-f9424ad58738\",\n"
                                            + "          \"first_name\": \"Bill\",\n"
                                            + "          \"last_name\": \"Roberts\",\n"
                                            + "          \"email\": \"bill.roberts@greatbrsolicitors.co.uk\",\n"
                                            + "          \"case_roles\": [\n"
                                            + "            \"[Claimant]\",\n"
                                            + "            \"[Defendant]\"\n"
                                            + "          ]\n"
                                            + "        }\n"
                                            + "      ]\n"
                                            + "    }    \n"
                                            + "  ]\n"
                                            + "}",
                                    mediaType = APPLICATION_JSON_VALUE)
                    })
            ),
            @ApiResponse(
                    code = 400,
                    message = "case_ids must be a non-empty list of proper case ids.",
                    examples = @Example({
                            @ExampleProperty(
                                    value = "{\"message\": \"case_ids must be a non-empty list of proper case ids\","
                                            + " \"status\": \"BAD_REQUEST\" }",
                                    mediaType = APPLICATION_JSON_VALUE)
                    })
            ),
            @ApiResponse(
                    code = 401,
                    message = "Authentication failure due to invalid / expired tokens (IDAM / S2S)."
            ),
            @ApiResponse(
                    code = 403,
                    message = "UnAuthorised S2S service.\n"
            )
    })
    public GetCaseAssignmentsResponse getCaseAssignments(@RequestParam("case_ids")
            @Valid @NotEmpty(message = "case_ids must be a non-empty list of proper case ids.") List<String> caseIds) {
        validateCaseIds(caseIds);
        List<CaseAssignedUsers> caseAssignedUsers = caseAssignmentService.getCaseAssignments(caseIds);
        return new GetCaseAssignmentsResponse(GET_ASSIGNMENTS_MESSAGE, caseAssignedUsers);
    }

    @DeleteMapping(path = "/case-assignments", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Unassign Access within Organisation", notes = "Unassign Access within Organisation")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponses({
        @ApiResponse(
            code = 201,
            message = UNASSIGN_ACCESS_MESSAGE
        ),
        @ApiResponse(
            code = 400,
            message = "One or more of the following reasons"
                + ": \n1. " + ValidationError.EMPTY_REQUESTED_UNASSIGNMENTS_LIST
                + ", \n2. " + ValidationError.CASE_ID_EMPTY
                + ", \n3. " + ValidationError.CASE_ID_INVALID
                + ", \n4. " + ValidationError.ASSIGNEE_ID_EMPTY
                + ", \n5. " + ValidationError.CASE_ROLE_FORMAT_INVALID
                + ", \n6. " + ValidationError.INVOKER_NOT_IN_SAME_ORGANISATION_AS_UNASSIGNED_USER + ".",
            response = ApiError.class,
            examples = @Example(value = {
                @ExampleProperty(
                    value = "{\n"
                        + "   \"status\": \"BAD_REQUEST\",\n"
                        + "   \"errors\": [\n"
                        + "      \"" + ValidationError.CASE_ID_INVALID + "\", \n"
                        + "      \"" + ValidationError.CASE_ROLE_FORMAT_INVALID + "\"\n"
                        + "   ]\n"
                        + "}",
                    mediaType = APPLICATION_JSON_VALUE
                )
            })
        ),
        @ApiResponse(
            code = 401,
            message = "Authentication failure due to invalid / expired tokens (IDAM / S2S)."
        ),
        @ApiResponse(
            code = 403,
            message = "One of the following reasons.\n"
                + "1) UnAuthorised S2S service \n"
                + "2) The user is neither a case access administrator nor a solicitor with access to the jurisdiction"
        )
    })
    public CaseUnassignmentResponse unassignAccessWithinOrganisation(
        @Valid @RequestBody CaseUnassignmentRequest requestPayload) {
        caseAssignmentService.unassignCaseAccess(requestPayload.getUnassignments());
        return new CaseUnassignmentResponse(UNASSIGN_ACCESS_MESSAGE);
    }

    private void validateCaseIds(List<String> caseIds) {
        caseIds.stream()
                .filter(caseId -> !StringUtils.isNumeric(caseId))
                .findAny()
                .ifPresent(caseId -> {
                    throw new ValidationException("Case ID should contain digits only");
                });

    }
}
