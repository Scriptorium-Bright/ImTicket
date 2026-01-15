import { redirect } from 'next/navigation'

export default function ExplorePage() {
    // For now, redirect to home as the list is there. 
    // In the future, this can be a full search/filter page.
    redirect('/')
}
