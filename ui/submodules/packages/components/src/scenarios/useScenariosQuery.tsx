import { UserData } from "nussknackerUi/common/models/User";
import { useContext, useMemo } from "react";
import { NkApiContext } from "../settings/nkApiProvider";
import { ProcessType } from "nussknackerUi/components/Process/types";
import { StatusesType } from "nussknackerUi/HttpService";
import { useQuery } from "react-query";
import { UseQueryResult } from "react-query/types/react/types";

function useScenariosQuery(): UseQueryResult<ProcessType[]> {
    const api = useContext(NkApiContext);
    return useQuery({
        queryKey: ["scenarios"],
        queryFn: async () => {
            const results = await Promise.all([api.fetchProcesses(), api.fetchProcesses({ isArchived: true })]);
            return results.flatMap(({ data }) => data);
        },
        enabled: !!api,
        refetchInterval: 60000,
    });
}

export function useScenariosStatusesQuery(): UseQueryResult<StatusesType> {
    const api = useContext(NkApiContext);
    return useQuery({
        queryKey: ["scenariosStatuses"],
        queryFn: async () => {
            const { data } = await api.fetchProcessesStates();
            return data;
        },
        enabled: !!api,
        refetchInterval: 15000,
    });
}

export function useUserQuery(): UseQueryResult<UserData> {
    const api = useContext(NkApiContext);
    return useQuery({
        queryKey: ["user"],
        queryFn: async () => {
            const { data } = await api.fetchLoggedUser();
            return data;
        },
        enabled: !!api,
        refetchInterval: 900000,
    });
}

export function useScenariosWithStatus(): UseQueryResult<ProcessType[]> {
    const scenarios = useScenariosQuery();
    const statuses = useScenariosStatusesQuery();
    return useMemo(() => {
        const { data = [] } = scenarios;
        return {
            ...scenarios,
            data: data.map((scenario) => ({
                ...scenario,
                state: statuses?.data?.[scenario.id] || scenario.state,
            })),
        } as UseQueryResult<ProcessType[]>;
    }, [scenarios, statuses]);
}
