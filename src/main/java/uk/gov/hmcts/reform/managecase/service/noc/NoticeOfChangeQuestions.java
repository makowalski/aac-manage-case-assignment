package uk.gov.hmcts.reform.managecase.service.noc;


import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.applicationinsights.boot.dependencies.apachecommons.lang3.ArrayUtils;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;
import uk.gov.hmcts.reform.managecase.api.errorhandling.CaseCouldNotBeFetchedException;
import uk.gov.hmcts.reform.managecase.api.payload.RequestNoticeOfChangeResponse;
import uk.gov.hmcts.reform.managecase.client.datastore.CaseResource;
import uk.gov.hmcts.reform.managecase.client.datastore.ChangeOrganisationRequest;
import uk.gov.hmcts.reform.managecase.client.datastore.model.CaseViewResource;
import uk.gov.hmcts.reform.managecase.client.datastore.model.elasticsearch.CaseSearchResultViewResource;
import uk.gov.hmcts.reform.managecase.client.datastore.model.elasticsearch.SearchResultViewHeader;
import uk.gov.hmcts.reform.managecase.client.datastore.model.elasticsearch.SearchResultViewHeaderGroup;
import uk.gov.hmcts.reform.managecase.client.datastore.model.elasticsearch.SearchResultViewItem;
import uk.gov.hmcts.reform.managecase.client.definitionstore.model.ChallengeQuestion;
import uk.gov.hmcts.reform.managecase.client.definitionstore.model.ChallengeQuestionsResult;
import uk.gov.hmcts.reform.managecase.domain.NoCRequestDetails;
import uk.gov.hmcts.reform.managecase.domain.Organisation;
import uk.gov.hmcts.reform.managecase.domain.OrganisationPolicy;
import uk.gov.hmcts.reform.managecase.repository.DataStoreRepository;
import uk.gov.hmcts.reform.managecase.repository.DefinitionStoreRepository;
import uk.gov.hmcts.reform.managecase.repository.PrdRepository;
import uk.gov.hmcts.reform.managecase.security.SecurityUtils;
import uk.gov.hmcts.reform.managecase.util.JacksonUtils;

import javax.validation.ValidationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.reform.managecase.api.controller.NoticeOfChangeController.REQUEST_NOTICE_OF_CHANGE_STATUS_MESSAGE;
import static uk.gov.hmcts.reform.managecase.api.errorhandling.ValidationError.CASE_ID_INVALID;
import static uk.gov.hmcts.reform.managecase.api.errorhandling.ValidationError.CASE_NOT_FOUND;
import static uk.gov.hmcts.reform.managecase.api.errorhandling.ValidationError.CHANGE_REQUEST;
import static uk.gov.hmcts.reform.managecase.api.errorhandling.ValidationError.INSUFFICIENT_PRIVILEGE;
import static uk.gov.hmcts.reform.managecase.api.errorhandling.ValidationError.NOC_EVENT_NOT_AVAILABLE;
import static uk.gov.hmcts.reform.managecase.api.errorhandling.ValidationError.NOC_REQUEST_ONGOING;
import static uk.gov.hmcts.reform.managecase.api.errorhandling.ValidationError.NO_ORG_POLICY_WITH_ROLE;
import static uk.gov.hmcts.reform.managecase.repository.DefaultDataStoreRepository.CHANGE_ORGANISATION_REQUEST;

@Service
@SuppressWarnings({"PMD.DataflowAnomalyAnalysis",
    "PMD.GodClass",
    "PMD.ExcessiveImports",
    "PMD.TooManyMethods",
    "PMD.AvoidDeeplyNestedIfStmts", "PMD.PreserveStackTrace", "PMD.LawOfDemeter",
    "PMD.AvoidLiteralsInIfCondition", "PMD.CyclomaticComplexity"})
public class NoticeOfChangeQuestions {

    public static final String PUI_ROLE = "pui-caa";

    private static final String APPROVED = "APPROVED";
    private static final String PENDING = "PENDING";
    private static final String CHALLENGE_QUESTION_ID = "NoCChallenge";
    private static final String CASE_ROLE_ID = "CaseRoleId";

    private final DataStoreRepository dataStoreRepository;
    private final DefinitionStoreRepository definitionStoreRepository;
    private final PrdRepository prdRepository;
    private final JacksonUtils jacksonUtils;
    private final SecurityUtils securityUtils;

    @Autowired
    public NoticeOfChangeQuestions(DataStoreRepository dataStoreRepository,
                                   DefinitionStoreRepository definitionStoreRepository,
                                   PrdRepository prdRepository,
                                 JacksonUtils jacksonUtils,
                                 SecurityUtils securityUtils) {
        this.dataStoreRepository = dataStoreRepository;
        this.definitionStoreRepository = definitionStoreRepository;
        this.prdRepository = prdRepository;
        this.jacksonUtils = jacksonUtils;
        this.securityUtils = securityUtils;
    }

    public ChallengeQuestionsResult getChallengeQuestions(String caseId) {
        ChallengeQuestionsResult challengeQuestionsResult = challengeQuestions(caseId).getChallengeQuestionsResult();

        List<ChallengeQuestion> challengeQuestionsResponse = challengeQuestionsResult.getQuestions().stream()
            .map(challengeQuestion -> {
                challengeQuestion.setAnswerField(null);
                return challengeQuestion;
            })
            .collect(Collectors.toList());


        return ChallengeQuestionsResult.builder().questions(challengeQuestionsResponse).build();
    }

    public NoCRequestDetails challengeQuestions(String caseId) {
        CaseViewResource caseViewResource = getCase(caseId);
        checkForCaseEvents(caseViewResource);
        CaseSearchResultViewResource caseSearchResultViewResource = findCaseBy(caseViewResource
                                                                                   .getCaseType().getId(), caseId);
        checkCaseFields(caseSearchResultViewResource);
        UserInfo userInfo = getUserInfo();
        validateUserRoles(caseViewResource, userInfo);
        String caseType = caseViewResource.getCaseType().getId();
        ChallengeQuestionsResult challengeQuestionsResult =
            definitionStoreRepository.challengeQuestions(caseType, CHALLENGE_QUESTION_ID);
        Optional<SearchResultViewItem> searchResultViewItem = caseSearchResultViewResource
            .getCases().stream().findFirst();
        if (searchResultViewItem.isPresent()) {
            List<OrganisationPolicy> organisationPolicies = searchResultViewItem.get().findPolicies();
            checkOrgPoliciesForRoles(challengeQuestionsResult, organisationPolicies);
        } else {
            throw new CaseCouldNotBeFetchedException(CASE_NOT_FOUND);
        }

        return NoCRequestDetails.builder()
            .caseViewResource(caseViewResource)
            .challengeQuestionsResult(challengeQuestionsResult)
            .searchResultViewItem(caseSearchResultViewResource.getCases().get(0))
            .build();
    }

    private void checkOrgPoliciesForRoles(ChallengeQuestionsResult challengeQuestionsResult,
                                          List<OrganisationPolicy> organisationPolicies) {
        if (organisationPolicies.isEmpty()) {
            throw new ValidationException(NO_ORG_POLICY_WITH_ROLE);
        }
        challengeQuestionsResult.getQuestions().forEach(challengeQuestion -> {
            boolean missingRole = challengeQuestion.getAnswers().stream()
                .anyMatch(answer -> !isRoleInOrganisationPolicies(organisationPolicies, answer.getCaseRoleId()));
            if (missingRole) {
                throw new ValidationException(NO_ORG_POLICY_WITH_ROLE);
            }
        });
    }

    private boolean isRoleInOrganisationPolicies(List<OrganisationPolicy> organisationPolicies, String role) {
        return organisationPolicies.stream()
            .anyMatch(organisationPolicy -> organisationPolicy.getOrgPolicyCaseAssignedRole().equals(role));
    }

    private List<OrganisationPolicy> findPolicies(CaseResource caseResource) {
        List<JsonNode> policyNodes = caseResource.findOrganisationPolicyNodes();
        return policyNodes.stream()
            .map(node -> jacksonUtils.convertValue(node, OrganisationPolicy.class))
            .collect(toList());
    }

    private void validateUserRoles(CaseViewResource caseViewResource, UserInfo userInfo) {
        List<String> roles = userInfo.getRoles();
        if (!roles.contains(PUI_ROLE)
            && !isActingAsSolicitor(roles, caseViewResource.getCaseType().getJurisdiction().getId())) {
            throw new ValidationException(INSUFFICIENT_PRIVILEGE);
        }
    }

    private boolean isActingAsSolicitor(List<String> roles, String jurisdiction) {
        return securityUtils.hasSolicitorRole(roles, jurisdiction);
    }

    private UserInfo getUserInfo() {
        return securityUtils.getUserInfo();
    }

    private CaseViewResource getCase(String caseId) {
        CaseViewResource caseViewResource = new CaseViewResource();
        try {
            caseViewResource = dataStoreRepository.findCaseByCaseId(caseId);
        } catch (FeignException e) {
            if (HttpStatus.NOT_FOUND.value() == e.status()) {
                throw new CaseCouldNotBeFetchedException(CASE_NOT_FOUND);
            } else if (HttpStatus.BAD_REQUEST.value() == e.status()) {
                throw new CaseCouldNotBeFetchedException(CASE_ID_INVALID);
            }
        }
        return caseViewResource;
    }

    private CaseResource getCaseViaExternalApi(String caseId) {
        return dataStoreRepository.findCaseByCaseIdExternalApi(caseId);
    }

    private CaseSearchResultViewResource findCaseBy(String caseTypeId, String caseId) {
        return dataStoreRepository.findCaseBy(caseTypeId, null, caseId);
    }

    private void checkCaseFields(CaseSearchResultViewResource caseDetails) {
        if (caseDetails.getCases().isEmpty()) {
            throw new CaseCouldNotBeFetchedException(CASE_NOT_FOUND);
        }
        List<SearchResultViewHeader> searchResultViewHeaderList = new ArrayList<>();
        Optional<SearchResultViewHeaderGroup> searchResultViewHeaderGroup =
            caseDetails.getHeaders().stream().findFirst();
        if (searchResultViewHeaderGroup.isPresent()) {
            searchResultViewHeaderList = searchResultViewHeaderGroup.get().getFields();
        }
        List<SearchResultViewHeader> filteredSearch =
            searchResultViewHeaderList.stream()
                .filter(searchResultViewHeader ->
                            searchResultViewHeader.getCaseFieldTypeDefinition().getId()
                                .equals(CHANGE_ORGANISATION_REQUEST)).collect(toList());
        if (filteredSearch.size() > 1) {
            throw new ValidationException(CHANGE_REQUEST);
        }
        SearchResultViewItem searchResultViewItem = caseDetails.getCases().get(0);
        Map<String, JsonNode> caseFields = searchResultViewItem.getFields();
        for (SearchResultViewHeader searchResultViewHeader : filteredSearch) {
            if (caseFields.containsKey(searchResultViewHeader.getCaseFieldId())) {
                JsonNode node = caseFields.get(searchResultViewHeader.getCaseFieldId());
                if (node != null) {
                    JsonNode caseRoleId = node.findPath(CASE_ROLE_ID);
                    if (!caseRoleId.isNull()) {
                        throw new ValidationException(NOC_REQUEST_ONGOING);
                    }
                }
            }
        }
    }

    private void checkForCaseEvents(CaseViewResource caseViewResource) {
        if (caseViewResource.getCaseViewActionableEvents() == null
            || ArrayUtils.isEmpty(caseViewResource.getCaseViewActionableEvents())) {
            throw new ValidationException(NOC_EVENT_NOT_AVAILABLE);
        }
    }

    /**
     * Input parameters to be decided on with Dan, dependant on response from NocAnswers.
     *
     * @param noCRequestDetails details of the NoCRequest provided by the call ot NocAnswers
     * @return RequestNoticeOfChangeResponse
     */
    public RequestNoticeOfChangeResponse requestNoticeOfChange(NoCRequestDetails noCRequestDetails) {
        String caseId = noCRequestDetails.getCaseViewResource().getReference();

        Organisation incumbentOrganisation = noCRequestDetails.getOrganisationPolicy().getOrganisation();
        String caseRoleId = noCRequestDetails.getOrganisationPolicy().getOrgPolicyCaseAssignedRole();

        String organisationIdentifier = prdRepository.findUsersByOrganisation().getOrganisationIdentifier();

        Organisation invokersOrganisation = new Organisation(organisationIdentifier, "");

        String eventId = getEventId(noCRequestDetails);

        // LLD Step 4: Generate the NoCRequest event:
        generateNoCRequestEvent(caseId, invokersOrganisation, incumbentOrganisation, caseRoleId, eventId);

        // LLD Step 5: Confirm if the NoCRequest has been auto-approved:
        // Reload the case data (EI-3); as the case may have been changed as a result of the post-submit callback to
        // CheckForNoCApproval operation, return error if detected (Response case - 7).
        CaseResource caseResource = getCaseViaExternalApi(caseId);

        boolean isApprovalComplete =
            isNocRequestAutoApprovalCompleted(caseResource, invokersOrganisation, caseRoleId);

        // LLD STep 6: Auto-assign relevant case-roles to the invoker if required, i.e.:
        if (isApprovalComplete &&  isActingAsSolicitor(getUserInfo().getRoles(), caseResource.getJurisdiction())) {
            autoAssignCaseRoles(caseResource, invokersOrganisation);
        }

        return RequestNoticeOfChangeResponse.builder()
            .caseRole(caseRoleId)
            .approvalStatus(isApprovalComplete ? APPROVED : PENDING)
            .status(REQUEST_NOTICE_OF_CHANGE_STATUS_MESSAGE)
            .build();
    }

    private String getEventId(NoCRequestDetails noCRequestDetails) {
        // previous NoC related service calls
        // (https://tools.hmcts.net/confluence/display/ACA/API+Operation%3A+Get+NoC+Questions)
        // will have validated that events exist.
        // Assuming a single event, so always take first array element
        return noCRequestDetails.getCaseViewResource().getCaseViewActionableEvents()[0].getId();
    }

    private CaseResource generateNoCRequestEvent(String caseId,
                                                 Organisation invokersOrganisation,
                                                 Organisation incumbentOrganisation,
                                                 String caseRoleId,
                                                 String eventId) {
        ChangeOrganisationRequest changeOrganisationRequest = ChangeOrganisationRequest.builder()
            .caseRoleId(caseRoleId)
            .organisationToAdd(invokersOrganisation)
            .organisationToRemove(incumbentOrganisation)
            .requestTimestamp(LocalDateTime.now())
            .build();

        return dataStoreRepository.submitEventForCase(caseId, eventId, changeOrganisationRequest);

        //        Generate the NoCRequest event:
        //        Call EI-1 to generate an event token for a NoCRequest event for the case, return error if detected
        //        (Response case - 7).
        //        Update the following ChangeOrganisationRequest fields and in doing so confirm that the outcome of the
        //        request is the same as requested in the supplied expected outcome
        //        OrganisationToAdd = Organisation of the invoker as identified in Step-2.
        //        OrganisationToRemove = the incumbent organisation as identified in Step-2.
        //        CaseRole = the CaseRole in the OrganisationPolicy as identified in Step-2.
        //        RequestTimestamp = now.
        //            Reason = as specified in the request (may be null if allowed by config).
        //        Call EI-2 to submit the NoCRequest event + event token, return error if detected (Response case - 7).
        //... this action will trigger a submitted callback to the CheckForNoCApproval operation, which will apply
        // additional processing in the event of auto-approval.
    }

    private boolean isNocRequestAutoApprovalCompleted(CaseResource caseResource,
                                                      Organisation invokersOrganisation,
                                                      String caseRoleId) {
        //        Confirm if the following are all true:
        //        ChangeOrganisationRequest.CaseRole is NULL – i.e. the request has been auto-approved and processed.
        //        ChangeOrganisationRequest.ApprovalStatus is NULL - i.e. NULL (approved) or 0 (pending).
        //            The organisation in the OrganisationPolicy identified by the CaseRole saved in Step-2 matches
        //            that of the organisation of the invoker (also found in Step-2) – i.e. the request was to add or
        //            replace representation and it was approved.
        //
        Optional<ChangeOrganisationRequest> changeOrganisationRequest = getChangeOrganisationRequest(caseResource);

        return changeOrganisationRequest.isPresent()
            && changeOrganisationRequest.get().getCaseRoleId() == null
            && isRequestToAddOrReplaceRepresentationAndApproved(caseResource, invokersOrganisation, caseRoleId);
    }

    private Optional<ChangeOrganisationRequest> getChangeOrganisationRequest(CaseResource caseResource) {
        Optional changeOrganisationRequest = Optional.empty();
        final Optional<JsonNode> changeOrganisationRequestNode = caseResource.findChangeOrganisationRequestNode();

        if (changeOrganisationRequestNode.isPresent()) {
            changeOrganisationRequest = Optional.of(jacksonUtils.convertValue(changeOrganisationRequestNode.get(),
                                                                              ChangeOrganisationRequest.class));
        }

        return changeOrganisationRequest;
    }

    private void autoAssignCaseRoles(CaseResource caseResource,
                                     Organisation invokersOrganisation) {
        //        From the case data reloaded in Step-5: find the Case Roles in any OrganisationPolicy associated with
        //        the Organisation of the invoker as identified in Step-2.
        //        Make a single call to EI-4 containing a list item for each case-role identified: holding
        //            case_id = as specified in the request
        //        user_id = invoker's IDAM ID
        //        case_role = case role as identified
        //        organisation_id = Organisation of the invoker as identified in Step-2.
        //... return error if detected (Response case - 7).
        List<String> invokerOrgPolicyRoles =
            findInvokerOrgPolicyRoles(caseResource, invokersOrganisation);

        dataStoreRepository.assignCase(invokerOrgPolicyRoles, caseResource.getReference(),
                                       getUserInfo().getUid(), invokersOrganisation.getOrganisationID());
    }

    private boolean isRequestToAddOrReplaceRepresentationAndApproved(CaseResource caseResource,
                                                                     Organisation organisation,
                                                                     String caseRoleId) {
        // 1. get all org policies from the caseResource
        // 2. find org policy that has case role is matching caseRoleId
        //3. check that organisation for that org policy matches the invokersOrganisation - match only on id
        return findInvokerOrgPolicyRoles(caseResource, organisation).contains(caseRoleId);
    }

    private List<String> findInvokerOrgPolicyRoles(CaseResource caseResource, Organisation organisation) {
        List<OrganisationPolicy> policies = findPolicies(caseResource);
        return policies.stream()
            .filter(policy -> policy.getOrganisation() != null
                && organisation.getOrganisationID().equalsIgnoreCase(policy.getOrganisation().getOrganisationID()))
            .map(OrganisationPolicy::getOrgPolicyCaseAssignedRole)
            .collect(toList());
    }
}
